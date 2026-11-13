package com.cafeorbe.gateway.seguridad;

import com.cafeorbe.gateway.filters.errors.RespuestaDeError;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeguridadGatewayTest {

    private final SimpleMeterRegistry metricas = new SimpleMeterRegistry();
    private final EstadoCompartidoEnMemoria estado = new EstadoCompartidoEnMemoria();
    private final Clock reloj = Clock.fixed(Instant.parse("2026-11-10T10:00:00Z"), ZoneOffset.UTC);
    private final LimiteDeFrecuenciaFilter limite = new LimiteDeFrecuenciaFilter(estado,
            new RespuestaDeError(new ObjectMapper()), new Auditoria(metricas), 3, reloj);
    private final AtomicInteger llegaron = new AtomicInteger();
    private final GatewayFilterChain cadena = i -> {
        llegaron.incrementAndGet();
        return Mono.empty();
    };

    private HttpStatus peticionDe(String usuario) {
        var builder = MockServerHttpRequest.get("/api/subastas");
        if (usuario != null) {
            builder.header("X-User-Id", usuario);
        }
        var intercambio = MockServerWebExchange.from(builder);
        limite.filter(intercambio, cadena).block();
        return (HttpStatus) intercambio.getResponse().getStatusCode();
    }

    @Test
    @DisplayName("SEG-03 · Sobre el límite por usuario responde 429 sin llegar al servicio; otro usuario no se ve afectado")
    void limitePorUsuario() {
        for (int i = 0; i < 3; i++) {
            assertThat(peticionDe("ana")).isNull();
        }
        for (int i = 0; i < 7; i++) {
            assertThat(peticionDe("ana")).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
        assertThat(peticionDe("luis")).isNull();
        assertThat(llegaron.get()).isEqualTo(4);
        assertThat(metricas.counter("cafeorbe.seguridad.eventos", "evento", "limite_excedido", "motivo",
                "rest_por_segundo").count()).isEqualTo(7);
    }

    @Test
    @DisplayName("SEG-03 · Una petición sin usuario autenticado (ruta pública) no se cuenta")
    void sinUsuarioNoSeLimita() {
        for (int i = 0; i < 10; i++) {
            assertThat(peticionDe(null)).isNull();
        }
        assertThat(llegaron.get()).isEqualTo(10);
    }

    @Test
    @DisplayName("Redis: cuenta con INCR, fija la expiración solo la primera vez y consulta la sesión cerrada")
    @SuppressWarnings("unchecked")
    void redis() {
        var redis = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> valores = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(valores);
        when(valores.increment("k")).thenReturn(Mono.just(1L), Mono.just(2L));
        when(redis.expire("k", Duration.ofSeconds(2))).thenReturn(Mono.just(true));
        when(redis.hasKey("cafeorbe:sesion:cerrada:j1")).thenReturn(Mono.just(true));
        var real = new EstadoCompartidoRedis(redis);

        assertThat(real.contar("k", Duration.ofSeconds(2)).block()).isEqualTo(1L);
        assertThat(real.contar("k", Duration.ofSeconds(2)).block()).isEqualTo(2L);
        verify(redis).expire("k", Duration.ofSeconds(2));
        assertThat(real.sesionCerrada("j1").block()).isTrue();
    }

    @Test
    @DisplayName("DIS-01 · Si Redis falla se deja pasar (fail-open) en lugar de tumbar toda la plataforma")
    @SuppressWarnings("unchecked")
    void redisCaido() {
        var redis = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> valores = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(valores);
        when(valores.increment(anyString())).thenReturn(Mono.error(new IllegalStateException("caído")));
        when(redis.hasKey(anyString())).thenReturn(Mono.error(new IllegalStateException("caído")));
        var real = new EstadoCompartidoRedis(redis);

        assertThat(real.contar("k", Duration.ofSeconds(2)).block()).isZero();
        assertThat(real.sesionCerrada("j").block()).isFalse();
        verify(redis, never()).expire(anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    @DisplayName("La IP de auditoría es la primera de X-Forwarded-For")
    void ipDeAuditoria() {
        var peticion = MockServerHttpRequest.get("/").header("X-Forwarded-For", "203.0.113.9, 10.0.0.1").build();
        assertThat(Auditoria.ipDe(peticion)).isEqualTo("203.0.113.9");
        assertThat(Auditoria.ipDe(MockServerHttpRequest.get("/").build())).isEqualTo("desconocida");
    }
}
