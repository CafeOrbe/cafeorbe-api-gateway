package com.cafeorbe.gateway.filters.auth;

import com.cafeorbe.contracts.Rol;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/** Valida el token de sesión emitido por identity (JWT HS256). */
@Component
public class ValidadorDeToken {

    public record Identidad(UUID id, String nombre, Rol rol) {
    }

    private final SecretKey clave;

    public ValidadorDeToken(@Value("${cafeorbe.jwt.secret}") String secreto) {
        this.clave = Keys.hmacShaKeyFor(secreto.getBytes(StandardCharsets.UTF_8));
    }

    /** @param cabecera valor de Authorization, con el prefijo {@code Bearer } */
    public Optional<Identidad> validar(String cabecera) {
        if (cabecera == null || !cabecera.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser().verifyWith(clave).build()
                    .parseSignedClaims(cabecera.substring(7).trim()).getPayload();
            return Optional.of(new Identidad(UUID.fromString(claims.getSubject()), claims.get("nombre", String.class),
                    Rol.valueOf(claims.get("rol", String.class))));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
