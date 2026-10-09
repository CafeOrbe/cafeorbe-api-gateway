package com.cafeorbe.gateway.filters;

import com.cafeorbe.gateway.filters.auth.AutenticacionFilter;
import com.cafeorbe.gateway.filters.auth.ValidadorDeToken;
import com.cafeorbe.gateway.filters.errors.RespuestaDeError;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AutenticacionFilterTest {

    private static final String SECRETO = "secreto-de-prueba-con-al-menos-32-bytes!!";

    private final ValidadorDeToken validador = new ValidadorDeToken(SECRETO);
    private final AutenticacionFilter filtro =
            new AutenticacionFilter(validador, new RespuestaDeError(new ObjectMapper()));
    private final AtomicReference<ServerWebExchange> recibido = new AtomicReference<>();
    private final GatewayFilterChain cadena = intercambio -> {
        recibido.set(intercambio);
        return Mono.empty();
    };

    private MockServerWebExchange ejecutar(MockServerHttpRequest.BaseBuilder<?> peticion) {
        var intercambio = MockServerWebExchange.from(peticion);
        filtro.filter(intercambio, cadena).block();
        return intercambio;
    }

    @Test
    @DisplayName("Los preflight de CORS (OPTIONS) pasan sin token")
    void preflightEsPublico() {
        ejecutar(MockServerHttpRequest.options("/api/subastas"));

        assertThat(recibido.get()).isNotNull();
    }

    @Test
    @DisplayName("El webhook de LiveKit por POST pasa sin token, pero por GET exige sesión")
    void webhookDeLiveKit() {
        ejecutar(MockServerHttpRequest.post("/api/streaming/webhooks/livekit"));
        assertThat(recibido.get()).isNotNull();

        recibido.set(null);
        var rechazado = ejecutar(MockServerHttpRequest.get("/api/streaming/webhooks/livekit"));
        assertThat(recibido.get()).isNull();
        assertThat(rechazado.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Sin cabecera Authorization o con un token inválido responde 401 y no llega al servicio")
    void sinSesion() {
        var sinToken = ejecutar(MockServerHttpRequest.get("/api/subastas"));
        var tokenRoto = ejecutar(MockServerHttpRequest.get("/api/subastas")
                .header(HttpHeaders.AUTHORIZATION, "Bearer x"));

        assertThat(recibido.get()).isNull();
        assertThat(sinToken.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(tokenRoto.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(validador.validar(null)).isEmpty();
        assertThat(validador.validar("Basic abc")).isEmpty();
    }

    @Test
    @DisplayName("Con token válido pasa la identidad en X-User-* y descarta las cabeceras que puso el cliente")
    void conSesion() {
        UUID usuario = UUID.randomUUID();
        String token = Jwts.builder().subject(usuario.toString()).claim("nombre", "José").claim("rol", "COMPRADOR")
                .signWith(Keys.hmacShaKeyFor(SECRETO.getBytes(StandardCharsets.UTF_8))).compact();

        ejecutar(MockServerHttpRequest.get("/api/subastas")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-User-Id", "suplantado"));

        HttpHeaders cabeceras = recibido.get().getRequest().getHeaders();
        assertThat(cabeceras.getFirst("X-User-Id")).isEqualTo(usuario.toString());
        assertThat(cabeceras.getFirst("X-User-Name")).isEqualTo("Jos%C3%A9");
        assertThat(cabeceras.getFirst("X-User-Role")).isEqualTo("COMPRADOR");
    }
}
