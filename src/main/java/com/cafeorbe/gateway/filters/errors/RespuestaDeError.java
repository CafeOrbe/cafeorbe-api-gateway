package com.cafeorbe.gateway.filters.errors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Escribe el formato uniforme de error {@code {status, mensaje, campos}} que usan todos los servicios. */
@Component
public class RespuestaDeError {

    private final ObjectMapper json;

    public RespuestaDeError(ObjectMapper json) {
        this.json = json;
    }

    public Mono<Void> escribir(ServerHttpResponse respuesta, HttpStatusCode estado, String mensaje) {
        if (respuesta.isCommitted()) {
            return Mono.empty();
        }
        respuesta.setStatusCode(estado);
        respuesta.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] cuerpo;
        try {
            cuerpo = json.writeValueAsBytes(Map.of("status", estado.value(), "mensaje", mensaje, "campos", Map.of()));
        } catch (JsonProcessingException e) {
            cuerpo = ("{\"status\":" + estado.value() + "}").getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = respuesta.bufferFactory().wrap(cuerpo);
        return respuesta.writeWith(Mono.just(buffer));
    }
}
