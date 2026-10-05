package com.cafeorbe.gateway.filters.errors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RespuestaDeError decide si puede escribir o si la respuesta ya está comprometida. Escribir dos veces en
 * la misma respuesta es lo que rompe las conexiones WebFlux, así que ese caso tiene que estar verificado.
 */
class RespuestaDeErrorTest {

    private MockServerHttpResponse respuesta() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/subastas").build()).getResponse();
    }

    @Test
    @DisplayName("Escribe el formato uniforme {status, mensaje, campos} con el status pedido")
    void escribeElFormatoUniforme() {
        var respuesta = respuesta();

        new RespuestaDeError(new ObjectMapper())
                .escribir(respuesta, HttpStatus.NOT_FOUND, "Ruta no encontrada").block();

        assertThat(respuesta.getStatusCode().value()).isEqualTo(404);
        assertThat(respuesta.getHeaders().getContentType()).isEqualTo(org.springframework.http.MediaType.APPLICATION_JSON);
        assertThat(respuesta.getBodyAsString().defaultIfEmpty("").block())
                .contains("\"status\":404")
                .contains("\"mensaje\":\"Ruta no encontrada\"")
                .contains("\"campos\":{}");
    }

    @Test
    @DisplayName("Si la respuesta ya está comprometida no se escribe nada (escribir dos veces rompe WebFlux)")
    void noEscribeSobreUnaRespuestaYaComprometida() {
        ServerHttpResponse respuesta = mock(ServerHttpResponse.class);
        when(respuesta.isCommitted()).thenReturn(true);

        var mono = new RespuestaDeError(new ObjectMapper())
                .escribir(respuesta, HttpStatus.NOT_FOUND, "Ruta no encontrada");

        assertThat(mono).isEqualTo(Mono.empty());
        verify(respuesta, never()).setStatusCode(any());
        verify(respuesta, never()).writeWith(any());
    }

    @Test
    @DisplayName("Si Jackson falla, responde al menos con el status en JSON en vez de cortar la conexión")
    void respaldoSiNoSePuedeSerializar() throws JsonProcessingException {
        var jsonRoto = mock(ObjectMapper.class);
        when(jsonRoto.writeValueAsBytes(any())).thenThrow(new JsonProcessingException("roto") { });
        var respuesta = respuesta();

        new RespuestaDeError(jsonRoto)
                .escribir(respuesta, HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servidor").block();

        assertThat(respuesta.getBodyAsString().defaultIfEmpty("").block()).isEqualTo("{\"status\":500}");
    }

    @Test
    @DisplayName("Un 5xx conserva su status: el respaldo no degrada el código HTTP")
    void elRespaldoConservaElStatus() throws JsonProcessingException {
        var jsonRoto = mock(ObjectMapper.class);
        when(jsonRoto.writeValueAsBytes(any())).thenThrow(new JsonProcessingException("roto") { });
        var respuesta = respuesta();

        new RespuestaDeError(jsonRoto).escribir(respuesta, HttpStatus.BAD_GATEWAY, "Tardó demasiado").block();

        assertThat(respuesta.getStatusCode().value()).isEqualTo(502);
    }
}
