package com.cafeorbe.gateway.filters.auth;

import com.cafeorbe.gateway.filters.errors.RespuestaDeError;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El filtro marca como públicas ciertas rutas. Se prueba directamente porque por HTTP el preflight lo
 * responde Spring antes de llegar aquí, y aun así es una regla de seguridad: un OPTIONS nunca debe
 * lanzar un 401 que rompa el CORS del navegador.
 */
class AutenticacionFilterTest {

    private final ValidadorDeToken validador = mock(ValidadorDeToken.class);
    private final AutenticacionFilter filtro =
            new AutenticacionFilter(validador, new RespuestaDeError(new ObjectMapper()));

    private GatewayFilterChain cadenaQuePasa() {
        var cadena = mock(GatewayFilterChain.class);
        when(cadena.filter(any())).thenReturn(Mono.empty());
        return cadena;
    }

    @Test
    @DisplayName("Un OPTIONS es público para cualquier ruta y no consulta el token")
    void elPreflightNuncaExigeToken() {
        var cadena = cadenaQuePasa();
        var intercambio = MockServerWebExchange.from(
                MockServerHttpRequest.options("/api/subastas").build());

        filtro.filter(intercambio, cadena).block();

        verify(cadena).filter(any());
        verify(validador, never()).validar(any());
    }

    @Test
    @DisplayName("El OPTIONS de una ruta de otro servicio también es público")
    void elPreflightNoDependeDeLaRuta() {
        var cadena = cadenaQuePasa();

        for (String ruta : new String[] { "/api/sesion", "/api/orbes/saldo", "/api/streaming/x/y",
                "/api/subastas/inexistente" }) {
            filtro.filter(MockServerWebExchange.from(MockServerHttpRequest.options(ruta).build()), cadena).block();
        }

        verify(cadena, times(4)).filter(any());
    }
}