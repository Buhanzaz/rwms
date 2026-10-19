package dev.buhanzaz.rwms.platform.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class AmqpRetirementArchitectureTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void targetSpringStarterHasNoAmqpRuntimeOrAutoConfiguration() throws IOException {
        Path root = findProjectRoot();
        Path starter = root.resolve("platform/spring-boot-starter");

        assertThat(isPresent("org.springframework.amqp.rabbit.core.RabbitTemplate")).isFalse();
        assertThat(Files.readString(starter.resolve("build.gradle.kts")))
                .doesNotContain("spring-boot-starter-amqp")
                .doesNotContain("testcontainers-rabbitmq");
        assertThat(Files.readString(starter.resolve(
                        "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")))
                .doesNotContain("Rabbit")
                .doesNotContain("AMQP")
                .doesNotContain("Amqp");
        assertThat(containsRegularFiles(starter.resolve("src/main/java/dev/buhanzaz/rwms/platform/rabbit")))
                .isFalse();
    }

    @Test
    void composeKeepsRabbitOnlyAsAnExplicitMediaCompatibilityProfile() throws IOException {
        JsonNode compose = YAML.readTree(Files.readString(findProjectRoot().resolve("compose.yaml")));
        JsonNode services = compose.required("services");
        JsonNode rabbit = services.required("media-compat-rabbitmq");

        assertThat(StreamSupport.stream(rabbit.required("profiles").spliterator(), false)
                        .map(JsonNode::asText))
                .containsExactly("media-compat-rabbit");
        assertThat(rabbit.required("hostname").asText()).isEqualTo("rabbitmq");
        assertThat(rabbit.required("labels").required("com.rwms.compatibility-scope").asText())
                .isEqualTo("media-only");
        assertThat(rabbit.required("labels").required("com.rwms.target-integration").asText())
                .isEqualTo("false");
        assertThat(StreamSupport.stream(
                                rabbit.required("networks").required("rwms-media-compat").required("aliases").spliterator(),
                                false)
                        .map(JsonNode::asText))
                .containsExactly("rabbitmq");

        services.properties().forEach(service -> {
            if (!service.getKey().equals("media-compat-rabbitmq")) {
                assertThat(service.getValue().path("depends_on").has("media-compat-rabbitmq"))
                        .as("target service %s must not depend on media-compat Rabbit", service.getKey())
                        .isFalse();
            }
        });
    }

    private static boolean isPresent(String className) {
        try {
            Class.forName(className, false, AmqpRetirementArchitectureTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private static boolean containsRegularFiles(Path directory) throws IOException {
        if (Files.notExists(directory)) {
            return false;
        }
        try (var files = Files.walk(directory)) {
            return files.anyMatch(Files::isRegularFile);
        }
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("compose.yaml"))
                    && Files.isDirectory(current.resolve("platform/spring-boot-starter"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("RWMS project root was not found");
    }
}
