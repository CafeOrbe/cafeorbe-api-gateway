package com.cafeorbe.gateway.filters.auth;

import com.cafeorbe.contracts.Rol;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/** Valida el token de sesión emitido por identity (JWT HS256). */
@Component
public class ValidadorDeToken {

    /** @param jti identificador de la sesión; es lo que identity anota cuando la sesión se cierra (SEG-05) */
    public record Identidad(UUID id, String nombre, Rol rol, String jti) {
    }

    /**
     * SEG-02 (verificación en menos de 5 ms en p95): el parser es inmutable y seguro entre hilos, así que se arma una vez.
     * Armarlo en cada petición carga los servicios de la librería (deserializador JSON, códecs) y, en frío, una
     * sola petición llegó a tardar 60 ms; con pocas muestras eso basta para pasar del 5 % lento.
     */
    private final JwtParser parser;

    public ValidadorDeToken(@Value("${cafeorbe.jwt.secret}") String secreto) {
        SecretKey clave = Keys.hmacShaKeyFor(secreto.getBytes(StandardCharsets.UTF_8));
        this.parser = Jwts.parser().verifyWith(clave).build();
        calentar(clave);
    }

    /**
     * Valida una vez un token propio al arrancar: la carga de clases y la primera compilación ocurren antes de
     * atender tráfico y no en la primera petición de un usuario.
     */
    private void calentar(SecretKey clave) {
        String propio = Jwts.builder().id("calentamiento").subject(new UUID(0, 0).toString())
                .claim("nombre", "calentamiento").claim("rol", Rol.COMPRADOR.name())
                .expiration(new Date(System.currentTimeMillis() + 60_000)).signWith(clave).compact();
        validar("Bearer " + propio);
    }

    /**
     * @param cabecera valor de Authorization, con el prefijo {@code Bearer }
     * @return vacío si falta, la firma no cuadra, venció o no trae {@code jti} (los tokens sin jti no se pueden
     *         revocar, así que ya no se aceptan)
     */
    public Optional<Identidad> validar(String cabecera) {
        if (cabecera == null || !cabecera.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        try {
            Claims claims = parser.parseSignedClaims(cabecera.substring(7).trim()).getPayload();
            if (claims.getId() == null || claims.getId().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new Identidad(UUID.fromString(claims.getSubject()), claims.get("nombre", String.class),
                    Rol.valueOf(claims.get("rol", String.class)), claims.getId()));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }
}
