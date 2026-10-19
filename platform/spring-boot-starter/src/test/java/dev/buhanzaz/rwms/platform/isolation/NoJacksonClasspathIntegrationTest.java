package dev.buhanzaz.rwms.platform.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class NoJacksonClasspathIntegrationTest {

    @Test
    void starterBootsInAnIsolatedJvmWithoutAnyJacksonRuntimeJar() throws Exception {
        String configuredClasspath = System.getProperty("rwms.test.runtime-classpath");
        assertThat(configuredClasspath).isNotBlank();

        List<String> isolatedEntries = Arrays.stream(configuredClasspath.split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .filter(entry -> !entry.toLowerCase(Locale.ROOT).contains("jackson"))
                .filter(entry -> !isOptionalKafkaRuntime(entry))
                .toList();
        assertThat(isolatedEntries).isNotEmpty();
        assertThat(isolatedEntries).noneMatch(entry -> entry.toLowerCase(Locale.ROOT).contains("jackson"));

        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                .toString();
        Process process = new ProcessBuilder(
                        javaExecutable,
                        "-cp",
                        String.join(File.pathSeparator, isolatedEntries),
                        NoJacksonStarterProbe.class.getName())
                .redirectErrorStream(true)
                .start();

        boolean completed = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(completed).as("isolated JVM should complete; output:%n%s", output).isTrue();
        assertThat(process.exitValue()).as("isolated JVM output:%n%s", output).isZero();
        assertThat(output).contains("NO_JACKSON_STARTER_OK");
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
    }

    private boolean isOptionalKafkaRuntime(String entry) {
        String normalized = entry.toLowerCase(Locale.ROOT);
        return normalized.contains("spring-cloud-stream")
                || normalized.contains("spring-cloud-function")
                || normalized.contains("spring-integration-kafka")
                || normalized.contains("spring-kafka")
                || normalized.contains("kafka-clients")
                || normalized.contains("zstd-jni");
    }
}
