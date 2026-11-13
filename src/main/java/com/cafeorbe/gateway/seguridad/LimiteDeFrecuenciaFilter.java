package com.cafeorbe.gateway.seguridad;

import com.cafeorbe.contracts.Cabeceras;
import com.cafeorbe.gateway.filters.errors.RespuestaDeError;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;

/**
 * SEG-03: límite de peticiones REST por usuario, compartido entre réplicas (ventana fija de 1 s en Redis).
 * El excedente se responde con 429 y motivo tipificado, sin llegar al servicio interno. Corre después de la
 * autenticación, así que la clave es el usuario ya verificado y no algo que el cliente pueda inventar.
 * Las rutas públicas (ingreso con Google) no se limitan aquí: los intentos repetidos los controla Google.
 */
@Component
public class LimiteDeFrecuenciaFilter implements GlobalFilter, Ordered {

    private static final Duration VENTANA = Duration.ofSeconds(2);

    private final EstadoCompartido estado;
    private final RespuestaDeError errores;
    private final Auditoria auditoria;
    private final long maximoPorSegundo;
    private final Clock reloj;

    @Autowired
    public LimiteDeFrecuenciaFilter(EstadoCompartido estado, RespuestaDeError errores, Auditoria auditoria,
                                    @Value("${cafeorbe.limite.rest.por-segundo:20}") long maximoPorSegundo) {
        this(estado, errores, auditoria, maximoPorSegundo, Clock.systemUTC());
    }

    LimiteDeFrecuenciaFilter(EstadoCompartido estado, RespuestaDeError errores, Auditoria auditoria,
                             long maximoPorSegundo, Clock reloj) {
        this.estado = estado;
        this.errores = errores;
        this.auditoria = auditoria;
        this.maximoPorSegundo = maximoPorSegundo;
        this.reloj = reloj;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange intercambio, GatewayFilterChain cadena) {
        String usuario = intercambio.getRequest().getHeaders().getFirst(Cabeceras.USUARIO_ID);
        if (usuario == null) {
            return cadena.filter(intercambio);
        }
        long segundo = reloj.millis() / 1000;
        String clave = "cafeorbe:rl:rest:" + usuario + ":" + segundo;
        return estado.contar(clave, VENTANA).flatMap(total -> {
            if (total > maximoPorSegundo) {
                auditoria.rechazo("limite_excedido", "rest_por_segundo", usuario, intercambio.getRequest());
                intercambio.getResponse().getHeaders().set("Retry-After", "1");
                return errores.escribir(intercambio.getResponse(), HttpStatus.TOO_MANY_REQUESTS,
                        "Demasiadas solicitudes, espera un momento");
            }
            return cadena.filter(intercambio);
        });
    }

    @Override
    public int getOrder() {
        // Justo después de AutenticacionFilter (-100), que es quien fija X-User-Id.
        return -90;
    }
}
