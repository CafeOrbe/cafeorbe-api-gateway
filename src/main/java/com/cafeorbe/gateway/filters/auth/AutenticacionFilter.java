package com.cafeorbe.gateway.filters.auth;

import com.cafeorbe.contracts.Cabeceras;
import com.cafeorbe.gateway.filters.errors.RespuestaDeError;
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

/**
 * Valida el token de sesión y le pasa la identidad a los servicios internos en las cabeceras X-User-*.
 * Las cabeceras X-User-* que traiga el cliente se descartan siempre: nadie puede suplantar a otro usuario.
 */
@Component
public class AutenticacionFilter implements GlobalFilter, Ordered {

    private final ValidadorDeToken validador;
    private final RespuestaDeError errores;

    public AutenticacionFilter(ValidadorDeToken validador, RespuestaDeError errores) {
        this.validador = validador;
        this.errores = errores;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange intercambio, GatewayFilterChain cadena) {
        ServerHttpRequest peticion = intercambio.getRequest();
        ServerHttpRequest.Builder limpia = peticion.mutate().headers(h -> {
            h.remove(HttpHeaders.HOST);
            h.remove(Cabeceras.USUARIO_ID);
            h.remove(Cabeceras.USUARIO_NOMBRE);
            h.remove(Cabeceras.USUARIO_ROL);
        });

        if (esPublica(peticion)) {
            return cadena.filter(intercambio.mutate().request(limpia.build()).build());
        }

        var identidad = validador.validar(peticion.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        if (identidad.isEmpty()) {
            return errores.escribir(intercambio.getResponse(), HttpStatus.UNAUTHORIZED, "Sesión requerida o expirada");
        }
        var usuario = identidad.get();
        limpia.header(Cabeceras.USUARIO_ID, usuario.id().toString())
                // Los nombres pueden llevar tildes: se codifican para que sean válidos como valor de cabecera.
                .header(Cabeceras.USUARIO_NOMBRE, URLEncoder.encode(usuario.nombre(), StandardCharsets.UTF_8))
                .header(Cabeceras.USUARIO_ROL, usuario.rol().name());
        return cadena.filter(intercambio.mutate().request(limpia.build()).build());
    }

    /** Solo el ingreso (POST /api/sesion) y los preflight de CORS no exigen token. */
    private static boolean esPublica(ServerHttpRequest peticion) {
        if (HttpMethod.OPTIONS.equals(peticion.getMethod())) {
            return true;
        }
        String ruta = peticion.getPath().value();
        return HttpMethod.POST.equals(peticion.getMethod()) && (ruta.equals("/api/sesion") || ruta.equals("/api/sesion/"));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
