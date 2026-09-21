package com.cafeorbe.gateway.filters.errors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

/** Manejo uniforme de errores hacia los servicios internos: no disponible, demasiado lento, ruta inexistente. */
@Component
@Order(-2)
public class ManejadorDeErrores implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ManejadorDeErrores.class);

    private final RespuestaDeError errores;

    public ManejadorDeErrores(RespuestaDeError errores) {
        this.errores = errores;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange intercambio, Throwable error) {
        HttpStatusCode estado;
        String mensaje;
        if (error instanceof ResponseStatusException e) {
            estado = e.getStatusCode();
            mensaje = mensajePara(estado);
        } else if (causa(error, ConnectException.class) || causa(error, "PrematureCloseException")) {
            estado = HttpStatus.SERVICE_UNAVAILABLE;
            mensaje = "El servicio no está disponible, intenta de nuevo en unos segundos";
        } else if (causa(error, TimeoutException.class) || causa(error, "ReadTimeoutException")) {
            estado = HttpStatus.GATEWAY_TIMEOUT;
            mensaje = "El servicio tardó demasiado en responder";
        } else {
            estado = HttpStatus.INTERNAL_SERVER_ERROR;
            mensaje = "Error interno del servidor";
        }
        if (estado.is5xxServerError()) {
            log.warn("{} {} → {} ({})", intercambio.getRequest().getMethod(), intercambio.getRequest().getPath(),
                    estado.value(), error.toString());
        }
        return errores.escribir(intercambio.getResponse(), estado, mensaje);
    }

    private static String mensajePara(HttpStatusCode estado) {
        if (estado.value() == 404) {
            return "Ruta no encontrada";
        }
        if (estado.value() == 504) {
            return "El servicio tardó demasiado en responder";
        }
        if (estado.value() == 503) {
            return "El servicio no está disponible, intenta de nuevo en unos segundos";
        }
        return estado.is4xxClientError() ? "Solicitud no válida" : "Error interno del servidor";
    }

    private static boolean causa(Throwable error, Class<? extends Throwable> tipo) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (tipo.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    private static boolean causa(Throwable error, String nombreSimple) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t.getClass().getSimpleName().equals(nombreSimple)) {
                return true;
            }
        }
        return false;
    }
}
