package com.cafeorbe.gateway.seguridad;

import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Estado que comparten todas las réplicas del gateway (y identity) en Redis.
 * <ul>
 *   <li>SEG-05: {@code cafeorbe:sesion:cerrada:{jti}} lo escribe identity al cerrar sesión.</li>
 *   <li>SEG-03: contadores por ventana de tiempo para el límite de peticiones por usuario.</li>
 * </ul>
 */
public interface EstadoCompartido {

    String PREFIJO_CERRADA = "cafeorbe:sesion:cerrada:";

    /** {@code true} si la sesión se cerró. Si Redis no responde devuelve {@code false}: ver la implementación. */
    Mono<Boolean> sesionCerrada(String jti);

    /** Suma 1 al contador de la clave y devuelve el total en la ventana actual. */
    Mono<Long> contar(String clave, Duration ventana);
}
