package dev.buhanzaz.rwms.auth.integration.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

class OAuthWarehouseExistenceClientTest {

    private static final UUID SPB = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MSK = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final AtomicInteger existenceCalls = new AtomicInteger();
    private final AtomicInteger redirectedCalls = new AtomicInteger();
    private final AtomicInteger responseDelayMillis = new AtomicInteger();
    private final AtomicReference<Response> tokenResponse = new AtomicReference<>();
    private final Map<String, Response> existenceResponses = new LinkedHashMap<>();
    private final AtomicReference<String> basicAuthorization = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        tokenResponse.set(json(200, "{\"access_token\":\"service-token\",\"token_type\":\"Bearer\",\"scope\":\"warehouse.read\"}"));
        server.createContext("/oauth2/token", exchange -> {
            tokenCalls.incrementAndGet();
            basicAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, tokenResponse.get());
        });
        server.createContext("/api/internal/warehouse/v1/warehouses", exchange -> {
            existenceCalls.incrementAndGet();
            String id = exchange.getRequestURI().getPath().split("/")[6];
            Response response = existenceResponses.getOrDefault(id, json(404, "{}"));
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer service-token");
            respond(exchange, response);
        });
        server.createContext("/redirected", exchange -> {
            redirectedCalls.incrementAndGet();
            respond(exchange, json(200, "{}"));
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void obtainsOneExactScopeTokenAndCallsExactSingularEndpointForEachWarehouse() {
        existenceResponses.put(SPB.toString(), active(SPB));
        existenceResponses.put(MSK.toString(), active(MSK));
        WarehouseExistenceClient client = client("s:e/ c", Duration.ofSeconds(2));

        client.requireActive(Set.of(SPB, MSK));

        assertThat(tokenCalls).hasValue(1);
        assertThat(existenceCalls).hasValue(2);
        String encoded = Base64.getEncoder().encodeToString(
                "auth-service:s%3Ae%2F+c".getBytes(StandardCharsets.UTF_8));
        assertThat(basicAuthorization).hasValue("Basic " + encoded);
    }

    @Test
    void rejectsNotFoundInactiveMismatchAndEveryMalformedStrictResponse() {
        assertStatus(SPB, json(404, "{}"), HttpStatus.UNPROCESSABLE_CONTENT);
        assertStatus(SPB, json(200, existence(SPB, 0, false)), HttpStatus.CONFLICT);
        assertStatus(SPB, json(200, existence(MSK, 0, true)), HttpStatus.BAD_GATEWAY);
        assertStatus(SPB, json(200, "{\"id\":\"" + SPB + "\",\"version\":0,\"active\":true,\"extra\":1}"), HttpStatus.BAD_GATEWAY);
        assertStatus(SPB, json(200, "{\"id\":\"" + SPB + "\",\"active\":true}"), HttpStatus.BAD_GATEWAY);
        assertStatus(SPB, json(200, "{\"id\":\"" + SPB + "\",\"version\":0,\"active\":true,\"active\":true}"), HttpStatus.BAD_GATEWAY);
        assertStatus(SPB, json(200, existence(SPB, -1, true)), HttpStatus.BAD_GATEWAY);
        assertStatus(SPB, new Response(200, "text/plain", existence(SPB, 0, true), Map.of()), HttpStatus.BAD_GATEWAY);
    }

    @Test
    void rejectsWrongTokenScopeAuthenticationFailuresAndUpstreamOutage() {
        tokenResponse.set(json(200, "{\"access_token\":\"token\",\"token_type\":\"Bearer\",\"scope\":\"warehouse.read rwms.read\"}"));
        assertThatThrownBy(() -> client("secret", Duration.ofSeconds(2)).requireActive(Set.of(SPB)))
                .isInstanceOfSatisfying(WarehouseValidationException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));

        tokenResponse.set(json(401, "{}"));
        assertThatThrownBy(() -> client("secret", Duration.ofSeconds(2)).requireActive(Set.of(SPB)))
                .isInstanceOfSatisfying(WarehouseValidationException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));

        tokenResponse.set(json(503, "{}"));
        assertThatThrownBy(() -> client("secret", Duration.ofSeconds(2)).requireActive(Set.of(SPB)))
                .isInstanceOfSatisfying(WarehouseValidationException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void classifiesWarehouseAuthorizationServerErrorsAndTimeoutsWithoutPartialSuccess() {
        assertStatus(SPB, json(401, "{}"), HttpStatus.BAD_GATEWAY);
        assertStatus(SPB, json(403, "{}"), HttpStatus.BAD_GATEWAY);
        assertStatus(SPB, json(500, "{}"), HttpStatus.SERVICE_UNAVAILABLE);

        existenceResponses.put(SPB.toString(), active(SPB));
        responseDelayMillis.set(200);
        assertThatThrownBy(() -> client("secret", Duration.ofMillis(30)).requireActive(Set.of(SPB)))
                .isInstanceOfSatisfying(WarehouseValidationException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void neverFollowsRedirects() {
        tokenResponse.set(new Response(302, "application/json", "{}", Map.of("Location", baseUrl + "/redirected")));

        assertThatThrownBy(() -> client("secret", Duration.ofSeconds(2)).requireActive(Set.of(SPB)))
                .isInstanceOf(WarehouseValidationException.class);
        assertThat(redirectedCalls).hasValue(0);
    }

    private void assertStatus(UUID id, Response response, HttpStatus status) {
        existenceResponses.put(id.toString(), response);
        assertThatThrownBy(() -> client("secret", Duration.ofSeconds(2)).requireActive(Set.of(id)))
                .isInstanceOfSatisfying(WarehouseValidationException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(status));
    }

    private WarehouseExistenceClient client(String secret, Duration readTimeout) {
        var properties = new WarehouseValidationProperties(
                        true,
                        baseUrl,
                        baseUrl + "/oauth2/token",
                        "auth-service",
                        secret,
                        Duration.ofSeconds(2),
                        readTimeout)
                .validateEnabledConfiguration();
        return new OAuthWarehouseExistenceClient(
                properties,
                JsonMapper.builder().build(),
                OAuthWarehouseExistenceClient.httpClient(properties.connectTimeout()));
    }

    private Response active(UUID id) {
        return json(200, existence(id, 0, true));
    }

    private String existence(UUID id, long version, boolean active) {
        return "{\"id\":\"" + id + "\",\"version\":" + version + ",\"active\":" + active + "}";
    }

    private Response json(int status, String body) {
        return new Response(status, "application/json", body, Map.of());
    }

    private void respond(HttpExchange exchange, Response response) throws IOException {
        if (responseDelayMillis.get() > 0) {
            try {
                Thread.sleep(responseDelayMillis.get());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        exchange.getResponseHeaders().set("Content-Type", response.contentType());
        response.headers().forEach(exchange.getResponseHeaders()::set);
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(response.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record Response(int status, String contentType, String body, Map<String, String> headers) {
    }
}
