package com.cafeorbe.gateway.seguridad;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Solo pruebas y una única instancia. */
@Component
@ConditionalOnProperty(name = "cafeorbe.estado.modo", havingValue = "memoria")
public class EstadoCompartidoEnMemoria implements EstadoCompartido {

    private final Set<String> cerradas = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicLong> contadores = new ConcurrentHashMap<>();

    public void cerrarSesion(String jti) {
        cerradas.add(jti);
    }

    public void reiniciarContadores() {
        contadores.clear();
    }

    @Override
    public Mono<Boolean> sesionCerrada(String jti) {
        return Mono.just(cerradas.contains(jti));
    }

    @Override
    public Mono<Long> contar(String clave, Duration ventana) {
        return Mono.just(contadores.computeIfAbsent(clave, k -> new AtomicLong()).incrementAndGet());
    }
}
