package dev.buhanzaz.wmspanel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class WarehouseAiQueryInterpreter {

    private static final Logger log = LoggerFactory.getLogger(WarehouseAiQueryInterpreter.class);

    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}]+");
    private static final Set<String> IGNORED_TOKENS = Set.of(
            "в", "на", "для", "из", "со", "по", "и",
            "штука", "штуки", "штук", "шт", "штуку",
            "одна", "один", "одно", "две", "два",
            "нужна", "нужен", "нужно", "найди", "покажи");

    private final ObjectMapper objectMapper;
    private final Environment environment;
    private final HttpClient httpClient;

    public WarehouseAiQueryInterpreter(ObjectMapper objectMapper, Environment environment) {
        this.objectMapper = objectMapper;
        this.environment = environment;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public SearchInterpretation interpret(String rawQuery, List<Warehouse> availableWarehouses) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return new SearchInterpretation(null, "", List.of(), false);
        }

        if (isConfigured()) {
            try {
                SearchInterpretation interpretation = interpretWithOpenAi(rawQuery, availableWarehouses);
                if (interpretation != null) {
                    return interpretation;
                }
            } catch (Exception ex) {
                log.warn("AI query interpretation failed, fallback to local parsing: {}", ex.getMessage());
            }
        }

        return interpretLocally(rawQuery, availableWarehouses);
    }

    public boolean isConfigured() {
        String apiKey = environment.getProperty("spring.ai.openai.chat.api-key");
        return apiKey != null && !apiKey.isBlank();
    }

    public String configuredModel() {
        return environment.getProperty("spring.ai.openai.chat.options.model", "mistral-small-latest");
    }

    private SearchInterpretation interpretWithOpenAi(String rawQuery, List<Warehouse> availableWarehouses) throws IOException, InterruptedException {
        String apiKey = environment.getProperty("spring.ai.openai.chat.api-key");
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        String warehousesDescription = availableWarehouses.stream()
                .map(warehouse -> warehouse.getName() + (warehouse.getCity() == null || warehouse.getCity().isBlank() ? "" : " (" + warehouse.getCity() + ")"))
                .reduce((left, right) -> left + ", " + right)
                .orElse("none");

        var payloadNode = objectMapper.createObjectNode();
        payloadNode.put("model", configuredModel());
        payloadNode.putArray("messages")
                .add(objectMapper.createObjectNode()
                        .put("role", "system")
                        .put("content", """
                                Ты разбираешь запрос менеджера по складскому поиску.
                                Нужно извлечь склад и поисковые термины.
                                Ищи только среди доступных складов.
                                Если склад не назван явно, warehouse_name и warehouse_city должны быть пустыми строками.
                                search_text должен содержать только полезные термины для поиска без слов про склад.
                                """))
                .add(objectMapper.createObjectNode()
                        .put("role", "user")
                        .put("content", "Доступные склады: " + warehousesDescription + "\nЗапрос: " + rawQuery));
        var schemaNode = objectMapper.createObjectNode();
        schemaNode.put("type", "object");
        var propertiesNode = objectMapper.createObjectNode();
        propertiesNode.set("warehouse_name", objectMapper.createObjectNode().put("type", "string"));
        propertiesNode.set("warehouse_city", objectMapper.createObjectNode().put("type", "string"));
        propertiesNode.set("search_text", objectMapper.createObjectNode().put("type", "string"));
        schemaNode.set("properties", propertiesNode);
        schemaNode.putArray("required")
                .add("warehouse_name")
                .add("warehouse_city")
                .add("search_text");
        schemaNode.put("additionalProperties", false);

        payloadNode.set("response_format", objectMapper.createObjectNode()
                .put("type", "json_schema")
                .set("json_schema", objectMapper.createObjectNode()
                        .put("name", "warehouse_ai_query")
                        .put("strict", true)
                        .set("schema", schemaNode)));
        String payload = payloadNode.toPrettyString();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(environment.getProperty("spring.ai.openai.chat.base-url", "https://api.mistral.ai/v1")))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("OpenAI API returned " + response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
        if (contentNode.isMissingNode() || contentNode.isNull()) {
            return null;
        }

        JsonNode parsed = objectMapper.readTree(contentNode.asText());
        Warehouse matchedWarehouse = findWarehouse(
                availableWarehouses,
                parsed.path("warehouse_name").asText(""),
                parsed.path("warehouse_city").asText(""));
        String searchText = normalizedSearchText(parsed.path("search_text").asText(rawQuery));
        return new SearchInterpretation(matchedWarehouse, searchText, toTokens(searchText), true);
    }

    private SearchInterpretation interpretLocally(String rawQuery, List<Warehouse> availableWarehouses) {
        String loweredQuery = rawQuery.toLowerCase(Locale.ROOT);
        Warehouse matchedWarehouse = null;
        String matchedAlias = null;

        for (Warehouse warehouse : availableWarehouses) {
            for (String alias : aliasesFor(warehouse)) {
                if (alias.length() < 2) {
                    continue;
                }
                if (loweredQuery.contains(alias) && (matchedAlias == null || alias.length() > matchedAlias.length())) {
                    matchedWarehouse = warehouse;
                    matchedAlias = alias;
                }
            }
        }

        String searchText = rawQuery;
        if (matchedWarehouse != null) {
            for (String alias : aliasesFor(matchedWarehouse)) {
                if (alias.length() < 2) {
                    continue;
                }
                searchText = searchText.replaceAll("(?iu)" + Pattern.quote(alias), " ");
            }
        }
        searchText = normalizedSearchText(searchText);
        return new SearchInterpretation(matchedWarehouse, searchText, toTokens(searchText), false);
    }

    private Warehouse findWarehouse(List<Warehouse> warehouses, String warehouseName, String warehouseCity) {
        for (Warehouse warehouse : warehouses) {
            boolean nameMatches = warehouseName == null || warehouseName.isBlank()
                    || warehouse.getName() != null && warehouse.getName().equalsIgnoreCase(warehouseName);
            boolean cityMatches = warehouseCity == null || warehouseCity.isBlank()
                    || warehouse.getCity() != null && warehouse.getCity().equalsIgnoreCase(warehouseCity);
            if (nameMatches && cityMatches) {
                return warehouse;
            }
        }

        String query = normalizedSearchText((warehouseName == null ? "" : warehouseName) + " " + (warehouseCity == null ? "" : warehouseCity));
        for (Warehouse warehouse : warehouses) {
            for (String alias : aliasesFor(warehouse)) {
                if (!query.isBlank() && query.contains(alias)) {
                    return warehouse;
                }
            }
        }
        return null;
    }

    private List<String> aliasesFor(Warehouse warehouse) {
        Set<String> aliases = new LinkedHashSet<>();
        addAlias(aliases, warehouse.getName());
        addAlias(aliases, warehouse.getCity());
        if (warehouse.getName() != null) {
            for (String part : NON_ALNUM.split(warehouse.getName().toLowerCase(Locale.ROOT))) {
                addAlias(aliases, part);
            }
        }
        if (warehouse.getCity() != null) {
            for (String part : NON_ALNUM.split(warehouse.getCity().toLowerCase(Locale.ROOT))) {
                addAlias(aliases, part);
            }
        }
        return new ArrayList<>(aliases);
    }

    private void addAlias(Set<String> aliases, String value) {
        if (value == null) {
            return;
        }
        String normalized = normalizedSearchText(value);
        if (!normalized.isBlank()) {
            aliases.add(normalized);
        }
    }

    private String normalizedSearchText(String value) {
        if (value == null) {
            return "";
        }
        return NON_ALNUM.matcher(value.toLowerCase(Locale.ROOT))
                .replaceAll(" ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private List<String> toTokens(String searchText) {
        if (searchText == null || searchText.isBlank()) {
            return List.of();
        }
        return List.of(searchText.split("\\s+")).stream()
                .map(token -> token.toLowerCase(Locale.ROOT))
                .filter(token -> !token.isBlank())
                .filter(token -> !IGNORED_TOKENS.contains(token))
                .filter(token -> !token.chars().allMatch(Character::isDigit))
                .distinct()
                .toList();
    }

    public record SearchInterpretation(Warehouse warehouse, String searchText, List<String> searchTokens, boolean aiUsed) {
    }
}
