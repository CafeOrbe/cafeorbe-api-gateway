package com.cafeorbe.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DIS-01: mientras una réplica reinicia, el ingress de Azure le sigue mandando parte del tráfico y la petición queda
 * colgada o recibe 502/503. El gateway corta las lecturas (GET) a tiempo y las reintenta; las escrituras no.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"cafeorbe.estado.modo=memoria", "cafeorbe.limite.rest.por-segundo=1000",
                "cafeorbe.seguridad.credencial-interna=credencial-de-prueba", "logging.structured.format.console=",
                "GATEWAY_LECTURA_TIMEOUT_MS=300"})
@AutoConfigureWebTestClient(timeout = "10s")
class ReintentoDeLecturasTest {

    static final String SECRETO = "cafeorbe-dev-jwt-secret-cambiar-en-prod-0123456789";

    /** Cuántas veces recibió cada ruta el servicio de mentira, y qué hace con las primeras. */
    static final AtomicInteger llamadas = new AtomicInteger();
    static volatile int primerasQueFallan;
    static volatile String comoFallan = "colgarse";

    static HttpServer servicio;

    static HttpServer servicio() {
        if (servicio == null) {
            try {
                servicio = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            // Varios hilos: una petición colgada no debe impedir que llegue el reintento.
            servicio.setExecutor(Executors.newCachedThreadPool());
            servicio.createContext("/", ReintentoDeLecturasTest::responder);
            servicio.start();
        }
        return servicio;
    }

    static void responder(HttpExchange intercambio) throws IOException {
        int numero = llamadas.incrementAndGet();
        if (numero <= primerasQueFallan) {
            if ("colgarse".equals(comoFallan)) {
                try {
                    Thread.sleep(2_000); // réplica que reinicia: no responde a tiempo
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                intercambio.sendResponseHeaders(503, -1); // réplica que todavía no está lista
                intercambio.close();
                return;
            }
        }
        byte[] cuerpo = ("{\"intento\":" + numero + "}").getBytes(StandardCharsets.UTF_8);
        intercambio.getResponseHeaders().add("Content-Type", "application/json");
        intercambio.sendResponseHeaders(200, cuerpo.length);
        intercambio.getResponseBody().write(cuerpo);
        intercambio.close();
    }

    @DynamicPropertySource
    static void rutas(DynamicPropertyRegistry registro) {
        String url = "http://localhost:" + servicio().getAddress().getPort();
        registro.add("IDENTITY_URL", () -> url);
        registro.add("AUCTION_URL", () -> url);
        registro.add("WALLET_URL", () -> url);
        registro.add("STREAMING_URL", () -> url);
    }

    @AfterAll
    static void apagar() {
        servicio.stop(0);
    }

    @Autowired WebTestClient http;

    @BeforeEach
    void reiniciar() {
        llamadas.set(0);
        primerasQueFallan = 0;
        comoFallan = "colgarse";
    }

    private static String sesion() {
        return "Bearer " + Jwts.builder().id(UUID.randomUUID().toString()).subject(UUID.randomUUID().toString())
                .claim("nombre", "Ana").claim("rol", "SUBASTADOR").expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(SECRETO.getBytes(StandardCharsets.UTF_8))).compact();
    }

    @Test
    @DisplayName("DIS-01 · Un GET que queda colgado en la réplica que reinicia se corta y se reintenta: el cliente recibe 200")
    void getColgadoSeReintenta() {
        primerasQueFallan = 2;
        long inicio = System.nanoTime();

        http.get().uri("/api/subastas/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, sesion())
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.intento").isEqualTo(3);

        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("DIS-01 · Un GET que recibe 503 de una réplica que no está lista se reintenta")
    void get503SeReintenta() {
        primerasQueFallan = 2;
        comoFallan = "503";

        http.get().uri("/api/orbes/saldo").header(HttpHeaders.AUTHORIZATION, sesion())
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.intento").isEqualTo(3);
    }

    @Test
    @DisplayName("DIS-01 · Si todos los intentos fallan responde 504 tras 1 + 9 intentos, sin esperar los 10 s generales")
    void getAgotaLosReintentos() {
        primerasQueFallan = 100;
        long inicio = System.nanoTime();

        http.get().uri("/api/streaming/subastas/" + UUID.randomUUID() + "/estado").header(HttpHeaders.AUTHORIZATION, sesion())
                .exchange().expectStatus().isEqualTo(504);

        assertThat(llamadas.get()).isEqualTo(10);
        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(6));
    }

    @Test
    @DisplayName("DIS-01 · Un POST no se reintenta (podría duplicar la operación) ni usa el tiempo límite corto")
    void postNoSeReintenta() {
        primerasQueFallan = 1; // se cuelga 2 s: más que el límite de lectura, menos que los 10 s generales

        http.post().uri("/api/subastas").header(HttpHeaders.AUTHORIZATION, sesion())
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.intento").isEqualTo(1);

        assertThat(llamadas.get()).isEqualTo(1);
    }
}
