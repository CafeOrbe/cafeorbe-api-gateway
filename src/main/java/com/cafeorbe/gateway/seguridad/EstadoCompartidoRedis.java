package com.cafeorbe.gateway.seguridad;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Redis compartido. Si Redis no responde a tiempo se deja pasar la petición (fail-open) y se registra:
 * una caída de Redis no debe dejar a toda la plataforma respondiendo 401 o 429 (DIS-01). El token sigue
 * teniendo que ser válido y no vencido; lo que se pierde durante la caída es solo la revocación anticipada.
 */
@Component
@ConditionalOnProperty(name = "cafeorbe.estado.modo", havingValue = "redis", matchIfMissing = true)
public class EstadoCompartidoRedis implements EstadoCompartido {

    private static final Logger log = LoggerFactory.getLogger(EstadoCompartidoRedis.class);
    /** Presupuesto de latencia de la verificación (SEG-02: menos de 5 ms en p95 dentro de la misma región). */
    private static final Duration ESPERA = Duration.ofMillis(250);

    private final ReactiveStringRedisTemplate redis;

    public EstadoCompartidoRedis(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<Boolean> sesionCerrada(String jti) {
        return redis.hasKey(PREFIJO_CERRADA + jti)
                .timeout(ESPERA)
                .onErrorResume(e -> {
                    log.warn("No se pudo consultar en Redis si la sesión está cerrada: {}", e.toString());
                    return Mono.just(false);
                });
    }

    @Override
    public Mono<Long> contar(String clave, Duration ventana) {
        return redis.opsForValue().increment(clave)
                .flatMap(total -> total == 1L ? redis.expire(clave, ventana).thenReturn(total) : Mono.just(total))
                .timeout(ESPERA)
                .onErrorResume(e -> {
                    log.warn("No se pudo contar en Redis la clave de límite: {}", e.toString());
                    return Mono.just(0L);
                });
    }
}
