package com.cafeorbe.gateway;

import com.cafeorbe.contracts.Cabeceras;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayTest {

    static final String SECRETO = "cafeorbe-dev-jwt-secret-cambiar-en-prod-0123456789";
    static final UUID ANA = UUID.randomUUID();

    static HttpServer aguasArriba;
    static int puertoCaido;

    @BeforeAll
    static void levantarServicioFalso() throws IOException {
        // Servicio interno de mentira: responde con la ruta y la identidad que recibió.
        aguasArriba = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        aguasArriba.createContext("/", intercambio -> {
            var h = intercambio.getRequestHeaders();
            String cuerpo = "{\"ruta\":\"" + intercambio.getRequestURI().getPath() + "\","
                    + "\"id\":\"" + h.getFirst(Cabeceras.USUARIO_ID) + "\","
                    + "\"nombre\":\"" + h.getFirst(Cabeceras.USUARIO_NOMBRE) + "\","
                    + "\"rol\":\"" + h.getFirst(Cabeceras.USUARIO_ROL) + "\"}";
            byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().add("Content-Type", "application/json");
            intercambio.sendResponseHeaders(200, bytes.length);
            intercambio.getResponseBody().write(bytes);
            intercambio.close();
        });
        aguasArriba.start();
        try (ServerSocket s = new ServerSocket(0)) {
            puertoCaido = s.getLocalPort(); // puerto libre y sin nadie escuchando
        }
    }

    @AfterAll
    static void apagar() {
        aguasArriba.stop(0);
    }

    @DynamicPropertySource
    static void rutas(DynamicPropertyRegistry registro) {
        String vivo = "http://localhost:" + aguasArriba().getAddress().getPort();
        registro.add("IDENTITY_URL", () -> vivo);
        registro.add("AUCTION_URL", () -> vivo);
        registro.add("WALLET_URL", () -> vivo);
        registro.add("STREAMING_URL", () -> "http://localhost:" + puertoCaido);
    }

    private static HttpServer aguasArriba() {
        try {
            if (aguasArriba == null) {
                levantarServicioFalso();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return aguasArriba;
    }

    @LocalServerPort int puerto;
    @Autowired WebTestClient http;

    private static String token(UUID id, String nombre, String rol, Instant vence) {
        return Jwts.builder().subject(id.toString()).claim("nombre", nombre).claim("rol", rol)
                .expiration(Date.from(vence))
                .signWith(Keys.hmacShaKeyFor(SECRETO.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private static String valido() {
        return "Bearer " + token(ANA, "Ana", "COMPRADOR", Instant.now().plusSeconds(600));
    }

    @Test
    @DisplayName("El ingreso (POST /api/sesion) es público y llega a identity sin identidad")
    void ingresoEsPublico() {
        http.post().uri("/api/sesion").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.ruta").isEqualTo("/api/sesion").jsonPath("$.id").isEqualTo("null");
    }

    @Test
    @DisplayName("Sin token o con token inválido/vencido responde 401 con el formato uniforme de error")
    void sinSesion() {
        http.get().uri("/api/subastas").exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.status").isEqualTo(401).jsonPath("$.mensaje").isEqualTo("Sesión requerida o expirada");
        http.get().uri("/api/subastas").header(HttpHeaders.AUTHORIZATION, "Bearer no-es-un-jwt")
                .exchange().expectStatus().isUnauthorized();
        http.get().uri("/api/subastas")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(ANA, "Ana", "COMPRADOR", Instant.now().minusSeconds(60)))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Con token válido enruta al servicio e inyecta X-User-Id, X-User-Name (codificado) y X-User-Role")
    void inyectaIdentidad() {
        http.get().uri("/api/subastas/mias")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(ANA, "José Ñandú", "SUBASTADOR", Instant.now().plusSeconds(600)))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.ruta").isEqualTo("/api/subastas/mias")
                .jsonPath("$.id").isEqualTo(ANA.toString())
                .jsonPath("$.nombre").isEqualTo("Jos%C3%A9+%C3%91and%C3%BA")
                .jsonPath("$.rol").isEqualTo("SUBASTADOR");
    }

    @Test
    @DisplayName("Las cabeceras X-User-* enviadas por el cliente no se aceptan: nadie suplanta a otro usuario")
    void noSePuedeSuplantar() {
        http.get().uri("/api/orbes/saldo").header(HttpHeaders.AUTHORIZATION, valido())
                .header(Cabeceras.USUARIO_ID, UUID.randomUUID().toString())
                .header(Cabeceras.USUARIO_ROL, "SUBASTADOR")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.id").isEqualTo(ANA.toString()).jsonPath("$.rol").isEqualTo("COMPRADOR");

        // También se descartan en las rutas públicas.
        http.post().uri("/api/sesion").header(Cabeceras.USUARIO_ID, UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectBody().jsonPath("$.id").isEqualTo("null");
    }

    @Test
    @DisplayName("Solo se exponen las rutas del MVP: /store y /shipping no existen (404 uniforme)")
    void rutasFueraDelMvp() {
        http.get().uri("/store/orbes").header(HttpHeaders.AUTHORIZATION, valido())
                .exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.status").isEqualTo(404).jsonPath("$.mensaje").isEqualTo("Ruta no encontrada");
        http.get().uri("/shipping/envios").header(HttpHeaders.AUTHORIZATION, valido())
                .exchange().expectStatus().isNotFound();
        // El endpoint interno de saldo no está detrás de /api/orbes, por eso no se enruta.
        http.get().uri("/internal/orbes/" + ANA + "/saldo").header(HttpHeaders.AUTHORIZATION, valido())
                .exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("Si un servicio interno está caído responde 503 con el formato uniforme de error")
    void servicioCaido() {
        http.get().uri("/api/streaming/subastas/" + UUID.randomUUID() + "/estado")
                .header(HttpHeaders.AUTHORIZATION, valido())
                .exchange().expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.mensaje").value(m -> org.assertj.core.api.Assertions.assertThat((String) m).contains("no está disponible"));
    }

    @Test
    @DisplayName("CORS: el preflight del origen permitido se responde sin exigir token; otro origen se rechaza")
    void cors() throws Exception {
        // Se usa un cliente HTTP real: WebTestClient no envía el host y Spring lo toma por un origen malformado.
        var cliente = java.net.http.HttpClient.newHttpClient();
        java.util.function.Function<String, java.net.http.HttpResponse<String>> preflight = origen -> {
            try {
                var req = java.net.http.HttpRequest
                        .newBuilder(java.net.URI.create("http://localhost:" + puerto + "/api/subastas"))
                        .method("OPTIONS", java.net.http.HttpRequest.BodyPublishers.noBody())
                        .header("Origin", origen)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type")
                        .build();
                return cliente.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };

        var permitido = preflight.apply("http://localhost:5173");
        org.assertj.core.api.Assertions.assertThat(permitido.statusCode()).isEqualTo(200);
        org.assertj.core.api.Assertions.assertThat(permitido.headers().firstValue("access-control-allow-origin"))
                .contains("http://localhost:5173");

        org.assertj.core.api.Assertions.assertThat(preflight.apply("http://sitio-malicioso.example").statusCode())
                .isEqualTo(403);
    }
}
