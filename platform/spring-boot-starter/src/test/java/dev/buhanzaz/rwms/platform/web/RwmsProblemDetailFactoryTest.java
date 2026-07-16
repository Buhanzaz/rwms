package dev.buhanzaz.rwms.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.platform.contracts.ApiProblem;
import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

class RwmsProblemDetailFactoryTest {

    private final RwmsProblemDetailFactory factory = new RwmsProblemDetailFactory();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void buildsAndSerializesTheCanonicalTechnicalApiProblem() throws Exception {
        CorrelationContext correlation = new CorrelationContext(
                UUID.fromString("7f1bcf5c-2b33-4f71-9d6d-fbc77fe8dc90"), null);

        ApiProblem problem = factory.create(
                URI.create("https://rwms.example/problems/conflict"),
                "Conflict",
                HttpStatus.CONFLICT,
                "The resource was changed by another request",
                URI.create("/api/warehouse/v1/warehouses/1"),
                "OPTIMISTIC_CONFLICT",
                correlation);

        String json = jsonMapper.writeValueAsString(problem);
        ApiProblem restored = jsonMapper.readValue(json, ApiProblem.class);
        var tree = jsonMapper.readTree(json);

        assertThat(restored).isEqualTo(problem);
        assertThat(problem.violations()).isEmpty();
        assertThat(tree.get("status").intValue()).isEqualTo(409);
        assertThat(tree.get("correlation").get("correlationId").stringValue())
                .isEqualTo("7f1bcf5c-2b33-4f71-9d6d-fbc77fe8dc90");
        assertThat(tree.has("correlationId")).isFalse();
    }
}
