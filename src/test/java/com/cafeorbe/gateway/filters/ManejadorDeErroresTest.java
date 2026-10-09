package com.cafeorbe.gateway.filters;

import com.cafeorbe.gateway.filters.errors.ManejadorDeErrores;
import com.cafeorbe.gateway.filters.errors.RespuestaDeError;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManejadorDeErroresTest {

    private final ManejadorDeErrores manejador = new ManejadorDeErrores(new RespuestaDeError(new ObjectMapper()));

    /** Simula la excepción de Reactor Netty, que el manejador reconoce por su nombre simple. */
    static class PrematureCloseException extends RuntimeException {
    }

    static class ReadTimeoutException extends RuntimeException {
    }

    private MockServerWebExchange manejar(Throwable error) {
        var intercambio = MockServerWebExchange.from(MockServerHttpRequest.get("/api/subastas"));
        manejador.handle(intercambio, error).block();
        return intercambio;
    }

    private void verificar(Throwable error, HttpStatus estado, String mensaje) {
        var respuesta = manejar(error).getResponse();

        assertThat(respuesta.getStatusCode()).isEqualTo(estado);
        assertThat(respuesta.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(respuesta.getBodyAsString().block())
                .contains("\"status\":" + estado.value()).contains(mensaje).contains("\"campos\":{}");
    }

    @Test
    @DisplayName("Una ruta inexistente responde 404 con un mensaje claro")
    void rutaInexistente() {
        verificar(new ResponseStatusException(HttpStatus.NOT_FOUND), HttpStatus.NOT_FOUND, "Ruta no encontrada");
    }

    @Test
    @DisplayName("Un 504 que ya trae Spring conserva su mensaje de tiempo agotado")
    void gatewayTimeoutDeSpring() {
        verificar(new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT), HttpStatus.GATEWAY_TIMEOUT,
                "El servicio tardó demasiado en responder");
    }

    @Test
    @DisplayName("Un 503 que ya trae Spring conserva su mensaje de servicio no disponible")
    void serviceUnavailableDeSpring() {
        verificar(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE), HttpStatus.SERVICE_UNAVAILABLE,
                "El servicio no está disponible");
    }

    @Test
    @DisplayName("Otros 4xx responden solicitud no válida y otros 5xx error interno")
    void otrosEstados() {
        verificar(new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED), HttpStatus.METHOD_NOT_ALLOWED,
                "Solicitud no válida");
        verificar(new ResponseStatusException(HttpStatus.BAD_GATEWAY), HttpStatus.BAD_GATEWAY,
                "Error interno del servidor");
    }

    @Test
    @DisplayName("Si el servicio interno rechaza la conexión responde 503, aunque el error venga envuelto")
    void conexionRechazada() {
        verificar(new RuntimeException("envuelto", new ConnectException("rechazada")),
                HttpStatus.SERVICE_UNAVAILABLE, "El servicio no está disponible");
    }

    @Test
    @DisplayName("Si el servicio interno cierra la conexión a medias responde 503")
    void conexionCerradaAMedias() {
        verificar(new RuntimeException(new PrematureCloseException()),
                HttpStatus.SERVICE_UNAVAILABLE, "El servicio no está disponible");
    }

    @Test
    @DisplayName("Si el servicio interno tarda demasiado responde 504")
    void tiempoAgotado() {
        verificar(new RuntimeException(new TimeoutException()), HttpStatus.GATEWAY_TIMEOUT,
                "El servicio tardó demasiado en responder");
        verificar(new RuntimeException(new ReadTimeoutException()), HttpStatus.GATEWAY_TIMEOUT,
                "El servicio tardó demasiado en responder");
    }

    @Test
    @DisplayName("Un error desconocido responde 500 sin filtrar detalles internos")
    void errorDesconocido() {
        verificar(new IllegalStateException("detalle interno"), HttpStatus.INTERNAL_SERVER_ERROR,
                "Error interno del servidor");
    }

    @Test
    @DisplayName("Si la respuesta ya se envió no se escribe una segunda")
    void respuestaYaEnviada() {
        var intercambio = MockServerWebExchange.from(MockServerHttpRequest.get("/api/subastas"));
        intercambio.getResponse().setComplete().block();

        manejador.handle(intercambio, new IllegalStateException("tarde")).block();

        assertThat(intercambio.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("Si el JSON de error no se puede armar, igual se responde con el estado")
    void jsonDeErrorRoto() throws JsonProcessingException {
        ObjectMapper roto = mock(ObjectMapper.class);
        when(roto.writeValueAsBytes(any())).thenThrow(new JsonProcessingException("roto") { });
        var intercambio = MockServerWebExchange.from(MockServerHttpRequest.get("/api/subastas"));

        new ManejadorDeErrores(new RespuestaDeError(roto)).handle(intercambio, new IllegalStateException("x")).block();

        assertThat(intercambio.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(intercambio.getResponse().getBodyAsString().block()).isEqualTo("{\"status\":500}");
    }
}
