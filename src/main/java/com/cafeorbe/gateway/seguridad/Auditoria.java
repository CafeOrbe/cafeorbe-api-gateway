package com.cafeorbe.gateway.seguridad;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

/**
 * Registro de auditoría de seguridad del gateway: una línea JSON por intento rechazado con evento, motivo,
 * usuario, IP, ruta y correlationId, y la métrica {@code cafeorbe_seguridad_eventos_total}. Nunca registra tokens.
 */
@Component
public class Auditoria {

    private static final Logger log = LoggerFactory.getLogger("cafeorbe.seguridad");

    private final MeterRegistry metricas;

    public Auditoria(MeterRegistry metricas) {
        this.metricas = metricas;
    }

    public void rechazo(String evento, String motivo, String usuario, ServerHttpRequest peticion) {
        metricas.counter("cafeorbe.seguridad.eventos", "evento", evento, "motivo", motivo).increment();
        log.atWarn()
                .addKeyValue("evento", evento)
                .addKeyValue("motivo", motivo)
                .addKeyValue("usuario", usuario == null ? "desconocido" : usuario)
                .addKeyValue("ip", ipDe(peticion))
                .addKeyValue("metodo", String.valueOf(peticion.getMethod()))
                .addKeyValue("ruta", peticion.getPath().value())
                .addKeyValue("correlationId", peticion.getHeaders().getFirst(CorrelacionFilter.CABECERA))
                .log("Evento de seguridad: {} ({})", evento, motivo);
    }

    public static String ipDe(ServerHttpRequest peticion) {
        String reenviada = peticion.getHeaders().getFirst("X-Forwarded-For");
        if (reenviada != null && !reenviada.isBlank()) {
            return reenviada.split(",")[0].trim();
        }
        var remota = peticion.getRemoteAddress();
        return remota == null || remota.getAddress() == null ? "desconocida" : remota.getAddress().getHostAddress();
    }
}
