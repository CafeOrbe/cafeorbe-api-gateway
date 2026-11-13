package com.cafeorbe.gateway.filters.auth;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import com.cafeorbe.contracts.Cabeceras;
import com.cafeorbe.gateway.filters.errors.RespuestaDeError;
import com.cafeorbe.gateway.seguridad.Auditoria;
import com.cafeorbe.gateway.seguridad.EstadoCompartido;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Valida el token de sesión y le pasa la identidad a los servicios internos en las cabeceras X-User-*.
 * <ul>
 *   <li>Las cabeceras X-User-* y X-Internal-Token que traiga el cliente se descartan siempre: nadie puede
 *       suplantar a otro usuario ni hacerse pasar por un servicio interno.</li>
 *   <li>SEG-02: a cada petición que sigue hacia un servicio se le agrega la credencial interna.</li>
 *   <li>SEG-05: un token de una sesión ya cerrada se rechaza con 401 aunque no haya vencido.</li>
 * </ul>
 */
@Component
public class AutenticacionFilter implements GlobalFilter, Ordered {

    public static final String CREDENCIAL_INTERNA = "X-Internal-Token";

    /** Webhook de LiveKit: no trae token de sesión; streaming-service lo autentica por la firma de LiveKit. */
    static final String RUTA_WEBHOOK_LIVEKIT = "/api/streaming/webhooks/livekit";
    /**
     * Rutas POST que no exigen sesión: el ingreso con Google (SEG-01: el único que existe, no hay login propio)
     * y el webhook de LiveKit.
     */
    private static final Set<String> POST_PUBLICOS = Set.of("/api/sesion/google", RUTA_WEBHOOK_LIVEKIT);

    private final ValidadorDeToken validador;
    private final RespuestaDeError errores;
    private final EstadoCompartido estado;
    private final Auditoria auditoria;
    private final String credencial;
    private Timer permitidas;
    private Timer rechazadas;
    private Timer consultasDeSesionCerrada;

    public AutenticacionFilter(ValidadorDeToken validador, RespuestaDeError errores, EstadoCompartido estado,
                               Auditoria auditoria,
                               @Value("${cafeorbe.seguridad.credencial-interna}") String credencial) {
        this.validador = validador;
        this.errores = errores;
        this.estado = estado;
        this.auditoria = auditoria;
        this.credencial = credencial;
    }

    @Autowired
    void medirCon(MeterRegistry metricas) {
        permitidas = timerDeVerificacion(metricas, "sesion", "permitida");
        rechazadas = timerDeVerificacion(metricas, "sesion", "rechazada");
        // Aparte de SEG-02: la consulta a Redis de si la sesión se cerró. Es una ida por red y con concurrencia
        // pesa más que la verificación; se mide sola para no mezclarla con la meta de SEG-02.
        consultasDeSesionCerrada = timerDeVerificacion(metricas, "sesion_cerrada", "consultada");
    }

    /**
     * SEG-02: cuánto suma la verificación a cada petición (meta: menos de 5 ms en p95). Buckets en 1, 5 y 10 ms
     * para comparar directo con la meta en el tablero técnico.
     */
    static Timer timerDeVerificacion(MeterRegistry metricas, String etapa, String resultado) {
        return Timer.builder("cafeorbe.seguridad.verificacion")
                .description("SEG-02: tiempo de la verificación de seguridad de cada petición")
                .tag("etapa", etapa).tag("resultado", resultado)
                .serviceLevelObjectives(Duration.ofMillis(1), Duration.ofMillis(5), Duration.ofMillis(10))
                .register(metricas);
    }

    private static void medir(Timer timer, long inicio) {
        if (timer != null) {
            timer.record(System.nanoTime() - inicio, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange intercambio, GatewayFilterChain cadena) {
        ServerHttpRequest peticion = intercambio.getRequest();
        ServerHttpRequest.Builder limpia = peticion.mutate().headers(h -> {
            h.remove(HttpHeaders.HOST);
            h.remove(Cabeceras.USUARIO_ID);
            h.remove(Cabeceras.USUARIO_NOMBRE);
            h.remove(Cabeceras.USUARIO_ROL);
            h.set(CREDENCIAL_INTERNA, credencial);
        });

        if (esPublica(peticion)) {
            return cadena.filter(intercambio.mutate().request(limpia.build()).build());
        }

        // SEG-02: se mide la verificación de la identidad y el rol (firma y vencimiento del token).
        long inicio = System.nanoTime();
        var identidad = validador.validar(peticion.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        medir(identidad.isPresent() ? permitidas : rechazadas, inicio);
        if (identidad.isEmpty()) {
            boolean traiaToken = peticion.getHeaders().getFirst(HttpHeaders.AUTHORIZATION) != null;
            auditoria.rechazo("sesion_rechazada", traiaToken ? "token_invalido_o_vencido" : "sin_token", null, peticion);
            return errores.escribir(intercambio.getResponse(), HttpStatus.UNAUTHORIZED, "Sesión requerida o expirada");
        }
        var usuario = identidad.get();
        long inicioConsulta = System.nanoTime();
        return estado.sesionCerrada(usuario.jti()).flatMap(cerrada -> {
            medir(consultasDeSesionCerrada, inicioConsulta);
            if (Boolean.TRUE.equals(cerrada)) {
                auditoria.rechazo("sesion_rechazada", "sesion_cerrada", usuario.id().toString(), peticion);
                return errores.escribir(intercambio.getResponse(), HttpStatus.UNAUTHORIZED,
                        "Sesión requerida o expirada");
            }
            limpia.header(Cabeceras.USUARIO_ID, usuario.id().toString())
                    // Los nombres pueden llevar tildes: se codifican para que sean válidos como valor de cabecera.
                    .header(Cabeceras.USUARIO_NOMBRE, URLEncoder.encode(usuario.nombre(), StandardCharsets.UTF_8))
                    .header(Cabeceras.USUARIO_ROL, usuario.rol().name());
            return cadena.filter(intercambio.mutate().request(limpia.build()).build());
        });
    }

    /** El ingreso, el webhook de LiveKit y los preflight de CORS no exigen token. */
    private static boolean esPublica(ServerHttpRequest peticion) {
        if (HttpMethod.OPTIONS.equals(peticion.getMethod())) {
            return true;
        }
        return HttpMethod.POST.equals(peticion.getMethod()) && POST_PUBLICOS.contains(peticion.getPath().value());
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
