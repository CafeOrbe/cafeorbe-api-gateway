package com.cafeorbe.gateway.seguridad;

import com.cafeorbe.gateway.filters.auth.AutenticacionFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * El gateway es público: sus métricas ({@code /actuator/prometheus}) solo las lee el recolector de
 * observabilidad, que presenta la credencial interna. La salud queda abierta para las sondas de Azure.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ProteccionDeActuator implements WebFilter {

    private final byte[] credencial;

    public ProteccionDeActuator(@Value("${cafeorbe.seguridad.credencial-interna}") String credencial) {
        this.credencial = credencial.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange intercambio, WebFilterChain cadena) {
        String ruta = intercambio.getRequest().getPath().value();
        if (!ruta.startsWith("/actuator") || ruta.equals("/actuator/health") || ruta.startsWith("/actuator/health/")) {
            return cadena.filter(intercambio);
        }
        String recibida = intercambio.getRequest().getHeaders().getFirst(AutenticacionFilter.CREDENCIAL_INTERNA);
        if (recibida != null && MessageDigest.isEqual(credencial, recibida.getBytes(StandardCharsets.UTF_8))) {
            return cadena.filter(intercambio);
        }
        intercambio.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        return intercambio.getResponse().setComplete();
    }
}
