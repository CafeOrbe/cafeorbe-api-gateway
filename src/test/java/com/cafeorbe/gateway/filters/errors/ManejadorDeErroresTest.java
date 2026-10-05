package com.cafeorbe.gateway.filters.errors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El manejador decide qué status y qué mensaje ve el cliente cuando algo falla hacia un servicio interno.
 * Los mensajes son una lista blanca: ningún camino puede devolver el texto de la excepción.
 */
class ManejadorDeErroresTest {

    private final ManejadorDeErrores manejador =
            new ManejadorDeErrores(new RespuestaDeError(new ObjectMapper()));

    /**
     * El manejador busca estas dos por nombre simple porque las lanzan librerías que no están en el
     * classpath del gateway. Se reproducen aquí con el mismo nombre para ejercitar esa búsqueda.
     */
    @SuppressWarnings("serial")
    private static final class PrematureCloseException extends RuntimeException {
        PrematureCloseException() {
            super("no exportado por el cliente");
        }
    }

    @SuppressWarnings("serial")
    private static final class ReadTimeoutException extends RuntimeException {
        ReadTimeoutException() {
            super("no exportado por el cliente");
        }
    }

    private MockServerHttpResponse invocar(Throwable error) {
        var intercambio = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/subastas").build());
        manejador.handle(intercambio, error).block();
        return (MockServerHttpResponse) intercambio.getResponse();
    }

    /** Spring 6 cambió getBodyAsString() de String a Mono&lt;String&gt;; el defaultIfEmpty evita un null. */
    private String cuerpo(MockServerHttpResponse respuesta) {
        return respuesta.getBodyAsString().defaultIfEmpty("").block();
    }

    private void comprobar(HttpStatusCode esperado, String mensaje, Throwable error) {
        var respuesta = invocar(error);
        assertThat(respuesta.getStatusCode().value()).isEqualTo(esperado.value());
        assertThat(cuerpo(respuesta))
                .contains("\"status\":" + esperado.value())
                .contains("\"mensaje\":\"" + mensaje + "\"");
    }

    @Nested
    class RespuestaStatusExceptionDelPropioGateway {

        @Test
        @DisplayName("404: ruta que no existe")
        void rutaInexistente() {
            comprobar(HttpStatus.NOT_FOUND, "Ruta no encontrada",
                    new ResponseStatusException(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("504: el servicio tardó demasiado (lotraduce el propio servicio, no el timeout de red)")
        void timeoutPropio() {
            comprobar(HttpStatus.GATEWAY_TIMEOUT, "El servicio tardó demasiado en responder",
                    new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT));
        }

        @Test
        @DisplayName("503: el servicio no está disponible")
        void noDisponiblePropio() {
            comprobar(HttpStatus.SERVICE_UNAVAILABLE,
                    "El servicio no está disponible, intenta de nuevo en unos segundos",
                    new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
        }

        @Test
        @DisplayName("Otro 4xx se agrupa como solicitud inválida, sin repetir el motivo del servicio")
        void otro4xx() {
            comprobar(HttpStatus.BAD_REQUEST, "Solicitud no válida",
                    new ResponseStatusException(HttpStatus.BAD_REQUEST));
        }

        @Test
        @DisplayName("Otro 5xx se agrupa como error interno, sin repetir el motivo del servicio")
        void otro5xx() {
            comprobar(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servidor",
                    new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR));
        }
    }

    @Nested
    class ErroresDeTransporte {

        @Test
        @DisplayName("Conexión rechazada: 503 con el mensaje de reintento")
        void conexionRechazada() {
            comprobar(HttpStatus.SERVICE_UNAVAILABLE,
                    "El servicio no está disponible, intenta de nuevo en unos segundos",
                    new IllegalStateException("envuelto", new ConnectException("connection refused")));
        }

        @Test
        @DisplayName("Cierre prematuro de la conexión: 503 (se reconoce por nombre de clase)")
        void cierrePrematuro() {
            comprobar(HttpStatus.SERVICE_UNAVAILABLE,
                    "El servicio no está disponible, intenta de nuevo en unos segundos",
                    new IllegalStateException(new PrematureCloseException()));
        }

        @Test
        @DisplayName("Timeout de lectura: 504 con el mensaje de lentitud")
        void timeoutDeLectura() {
            comprobar(HttpStatus.GATEWAY_TIMEOUT, "El servicio tardó demasiado en responder",
                    new IllegalStateException("envuelto", new TimeoutException("read timed out")));
        }

        @Test
        @DisplayName("Timeout de lectura de Reactor Netty: 504 (se reconoce por nombre de clase)")
        void timeoutDeReactor() {
            comprobar(HttpStatus.GATEWAY_TIMEOUT, "El servicio tardó demasiado en responder",
                    new IllegalStateException(new ReadTimeoutException()));
        }

        @Test
        @DisplayName("La causa se busca a lo largo de toda la cadena, no solo en el primer nivel")
        void causaAnidada() {
            comprobar(HttpStatus.SERVICE_UNAVAILABLE,
                    "El servicio no está disponible, intenta de nuevo en unos segundos",
                    new RuntimeException("nivel 1",
                            new IllegalStateException("nivel 2",
                                    new RuntimeException("nivel 3", new ConnectException()))));
        }
    }

    @Nested
    class ErroresDesconocidos {

        @Test
        @DisplayName("Una excepción que no encaja en ningún caso da 500 genérico")
        void excepcionGenerica() {
            comprobar(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servidor",
                    new IllegalArgumentException("detalle que no debe verse"));
        }

        @Test
        @DisplayName("Nunca se filtra el texto de la excepción, ni siquiera anidada en la causa")
        void noSeFiltraElDetalle() {
            var cuerpo = cuerpo(invocar(new IllegalStateException("jdbc:postgresql://host:5432/db usuario root",
                    new ConnectException("ECONNREFUSED 10.0.0.7:5432"))));
            assertThat(cuerpo)
                    .doesNotContain("jdbc")
                    .doesNotContain("root")
                    .doesNotContain("10.0.0.7")
                    .doesNotContain("ECONNREFUSED");
        }

        @Test
        @DisplayName("Una causa nula no entra en bucle infinito")
        void sinCausa() {
            comprobar(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servidor",
                    new IllegalStateException());
        }
    }
}