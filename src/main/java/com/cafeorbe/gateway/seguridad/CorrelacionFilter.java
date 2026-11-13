package com.cafeorbe.gateway.seguridad;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * OBS-01: toda petición sale del gateway con un {@code X-Correlation-Id}. Si el cliente trae uno con formato
 * válido se respeta (sirve para seguir una puja desde el navegador); si no, se crea. Se devuelve también en la
 * respuesta para que el operador pueda buscarlo en Log Analytics.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelacionFilter implements WebFilter {

    public static final String CABECERA = "X-Correlation-Id";
    private static final Pattern VALIDO = Pattern.compile("[A-Za-z0-9-]{8,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange intercambio, WebFilterChain cadena) {
        String recibido = intercambio.getRequest().getHeaders().getFirst(CABECERA);
        String id = recibido != null && VALIDO.matcher(recibido).matches() ? recibido : UUID.randomUUID().toString();
        var peticion = intercambio.getRequest().mutate().headers(h -> h.set(CABECERA, id)).build();
        intercambio.getResponse().getHeaders().set(CABECERA, id);
        return cadena.filter(intercambio.mutate().request(peticion).build());
    }
}
