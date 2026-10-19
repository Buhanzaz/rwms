package dev.buhanzaz.rwms.auth.integration.warehouse;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Warehouse-validation adapter that uses a narrowly scoped OAuth client-credentials token to call
 * Warehouse Service's private existence endpoint.
 *
 * <p>The adapter validates both OAuth and existence response shapes strictly, follows no redirects,
 * and keeps no token cache. It is instantiated only when warehouse validation is enabled.
 */
final class OAuthWarehouseExistenceClient implements WarehouseExistenceClient {

    private static final Set<String> EXISTENCE_FIELDS = Set.of("id", "version", "active");
    private static final Set<String> TOKEN_SCOPE = Set.of("warehouse.read");

    private final WarehouseValidationProperties.Validated properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /**
     * Creates a strict-response adapter from already validated integration properties.
     *
     * @param properties validated private endpoints, credentials, and bounded timeouts
     * @param objectMapper base mapper used to create a duplicate-key-detecting mapper
     * @param httpClient client with the configured bounded connection timeout
     */
    OAuthWarehouseExistenceClient(
            WarehouseValidationProperties.Validated properties,
            ObjectMapper objectMapper,
            HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper.rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        this.httpClient = httpClient;
    }

    /**
     * Obtains one exact-scope access token and verifies every requested warehouse before the caller
     * may persist its mutation.
     *
     * @param warehouseIds distinct canonical warehouse identifiers to validate
     */
    @Override
    public void requireActive(Set<UUID> warehouseIds) {
        if (warehouseIds.isEmpty()) {
            return;
        }
        String accessToken = requestAccessToken();
        for (UUID warehouseId : warehouseIds) {
            validateWarehouse(warehouseId, accessToken);
        }
    }

    /** Obtains and validates a client-credentials token limited to {@code warehouse.read}. */
    private String requestAccessToken() {
        String credentials = formEncode(properties.clientId()) + ":" + formEncode(properties.clientSecret());
        String body = "grant_type=client_credentials&scope="
                + URLEncoder.encode("warehouse.read", StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(properties.tokenUri())
                .timeout(properties.readTimeout())
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        credentials.getBytes(StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = exchange(request, "OAuth token endpoint");
        if (response.statusCode() >= 500) {
            throw WarehouseValidationException.unavailable("OAuth token endpoint is unavailable", null);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw WarehouseValidationException.badGateway(
                    "OAuth token endpoint rejected auth-service credentials");
        }
        requireJsonContentType(response, "OAuth token endpoint");
        try {
            JsonNode json = objectMapper.readTree(response.body());
            JsonNode token = json.get("access_token");
            JsonNode type = json.get("token_type");
            JsonNode scope = json.get("scope");
            if (token == null || !token.isTextual() || token.textValue().isBlank()
                    || type == null || !type.isTextual() || !"bearer".equalsIgnoreCase(type.textValue())
                    || scope == null || !scope.isTextual() || !TOKEN_SCOPE.equals(scopes(scope.textValue()))) {
                throw WarehouseValidationException.badGateway(
                        "OAuth token response must grant exactly warehouse.read");
            }
            return token.textValue();
        } catch (WarehouseValidationException exception) {
            throw exception;
        } catch (tools.jackson.core.JacksonException exception) {
            throw WarehouseValidationException.badGateway("OAuth token response is malformed");
        }
    }

    /** Calls the private existence endpoint and maps its status to an authorization-safe failure. */
    private void validateWarehouse(UUID requestedId, String accessToken) {
        URI uri = URI.create(properties.baseUrl()
                + "/api/internal/warehouse/v1/warehouses/"
                + requestedId
                + "/existence");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.readTimeout())
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        HttpResponse<String> response = exchange(request, "Warehouse Service");
        if (response.statusCode() == 404) {
            throw WarehouseValidationException.notFound("Склад не найден: " + requestedId);
        }
        if (response.statusCode() >= 500 || response.statusCode() == 429) {
            throw WarehouseValidationException.unavailable("Warehouse Service is unavailable", null);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw WarehouseValidationException.badGateway(
                    "Warehouse Service rejected auth-service warehouse lookup");
        }
        requireJsonContentType(response, "Warehouse Service");
        validateExistenceResponse(requestedId, response.body());
    }

    /**
     * Accepts only the strict, minimal existence representation for the identifier that was asked
     * for and rejects inactive warehouses.
     */
    private void validateExistenceResponse(UUID requestedId, String body) {
        try {
            JsonNode json = objectMapper.readTree(body);
            if (json == null || !json.isObject() || json.size() != EXISTENCE_FIELDS.size()) {
                throw WarehouseValidationException.badGateway("Warehouse existence response has invalid fields");
            }
            Set<String> fields = new HashSet<>(json.propertyNames());
            JsonNode id = json.get("id");
            JsonNode version = json.get("version");
            JsonNode active = json.get("active");
            if (!EXISTENCE_FIELDS.equals(fields)
                    || id == null || !id.isTextual()
                    || version == null
                    || !version.isIntegralNumber()
                    || !version.canConvertToLong()
                    || version.longValue() < 0
                    || active == null || !active.isBoolean()) {
                throw WarehouseValidationException.badGateway("Warehouse existence response has invalid fields");
            }
            UUID responseId;
            try {
                responseId = UUID.fromString(id.textValue());
            } catch (IllegalArgumentException exception) {
                throw WarehouseValidationException.badGateway(
                        "Warehouse existence response identifier is malformed");
            }
            if (!responseId.toString().equals(id.textValue())) {
                throw WarehouseValidationException.badGateway(
                        "Warehouse existence response identifier is not canonical");
            }
            if (!requestedId.equals(responseId)) {
                throw WarehouseValidationException.badGateway("Warehouse Service returned a different warehouse id");
            }
            if (!active.booleanValue()) {
                throw WarehouseValidationException.conflict("Склад деактивирован: " + requestedId);
            }
        } catch (WarehouseValidationException exception) {
            throw exception;
        } catch (tools.jackson.core.JacksonException exception) {
            throw WarehouseValidationException.badGateway("Warehouse existence response is malformed");
        }
    }

    /**
     * Executes one bounded request and translates transport failures without exposing upstream
     * implementation details to an API caller.
     */
    private HttpResponse<String> exchange(HttpRequest request, String upstream) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException | ConnectException exception) {
            throw WarehouseValidationException.unavailable(
                    upstream + " timed out or refused the connection", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw WarehouseValidationException.unavailable(upstream + " request was interrupted", exception);
        } catch (IOException exception) {
            throw WarehouseValidationException.unavailable(upstream + " is unavailable", exception);
        }
    }

    /** Splits an OAuth scope response into its distinct space-delimited scope names. */
    private Set<String> scopes(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return new HashSet<>(java.util.Arrays.asList(value.trim().split("\\s+")));
    }

    /** Encodes one credential component for the OAuth form/basic-auth construction. */
    private String formEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Requires an application/json response before its body is parsed as a trusted payload. */
    private void requireJsonContentType(HttpResponse<?> response, String upstream) {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (!contentType.toLowerCase(java.util.Locale.ROOT).matches("application/json(?:\\s*;.*)?")) {
            throw WarehouseValidationException.badGateway(upstream + " response must be application/json");
        }
    }

    /** Creates the HTTP client with the integration's bounded connect timeout. */
    static HttpClient httpClient(Duration connectTimeout) {
        return HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }
}
