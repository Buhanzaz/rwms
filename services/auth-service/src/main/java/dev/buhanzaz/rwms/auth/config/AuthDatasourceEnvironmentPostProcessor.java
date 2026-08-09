package dev.buhanzaz.rwms.auth.config;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Rejects missing or development datasource settings before Spring creates the auth application context.
 *
 * <p>The processor runs immediately after configuration data is loaded, before auto-configuration can
 * initialize Flyway, JPA, or a {@code DataSource}. Error messages identify only the required environment
 * variable and never include a URL, username, or password value.</p>
 */
public final class AuthDatasourceEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String DATASOURCE_URL = "spring.datasource.url";
    static final String DATASOURCE_USERNAME = "spring.datasource.username";
    static final String DATASOURCE_PASSWORD = "spring.datasource.password";

    private static final String LOCAL_USERNAME = "rwms_auth";
    private static final String LOCAL_PASSWORD = "rwms_auth";
    private static final Set<String> LOOPBACK_HOSTS = Set.of(
            "localhost", "0.0.0.0", "::1", "0:0:0:0:0:0:0:1");

    /** Creates the stateless processor instantiated through Spring factories. */
    public AuthDatasourceEnvironmentPostProcessor() {
    }

    /**
     * Validates the fully loaded environment before application-context initialization.
     *
     * @param environment configuration environment containing base and profile-specific values
     * @param application application whose context has not yet been created
     */
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        validate(environment);
    }

    /**
     * Runs after Spring's config-data processor so profile YAML and deployment overrides are available.
     *
     * @return the environment-processor order immediately following configuration loading
     */
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    /**
     * Applies the datasource policy to the effective environment. Package visibility keeps the
     * early-startup rule directly testable without exposing a second runtime entry point.
     *
     * @param environment effective application environment
     */
    static void validate(Environment environment) {
        if (!requiresProductionSafety(environment)) {
            return;
        }
        requireProductionUrl(environment.getProperty(DATASOURCE_URL));
        requireProductionUsername(environment.getProperty(DATASOURCE_USERNAME));
        requireProductionPassword(environment.getProperty(DATASOURCE_PASSWORD));
    }

    private static boolean requiresProductionSafety(Environment environment) {
        boolean productionProfile = environment.acceptsProfiles(Profiles.of("prod", "production"));
        boolean developmentProfile = environment.acceptsProfiles(Profiles.of("dev", "test"));
        return productionProfile || !developmentProfile;
    }

    private static void requireProductionUrl(String value) {
        if (value == null || value.isBlank()) {
            throw unsafe("AUTH_DB_URL is required outside dev/test");
        }
        try {
            if (!value.startsWith("jdbc:")) {
                throw unsafe("AUTH_DB_URL must be a non-loopback PostgreSQL JDBC URL outside dev/test");
            }
            URI uri = URI.create(value.substring("jdbc:".length()));
            String host = uri.getHost();
            if (!"postgresql".equalsIgnoreCase(uri.getScheme())
                    || host == null
                    || uri.getUserInfo() != null
                    || isLoopback(host)) {
                throw unsafe("AUTH_DB_URL must be a non-loopback PostgreSQL JDBC URL outside dev/test");
            }
        } catch (IllegalArgumentException exception) {
            throw unsafe("AUTH_DB_URL must be a non-loopback PostgreSQL JDBC URL outside dev/test");
        }
    }

    private static void requireProductionUsername(String value) {
        if (value == null || value.isBlank()) {
            throw unsafe("AUTH_DB_USERNAME is required outside dev/test");
        }
        if (LOCAL_USERNAME.equals(value.trim())) {
            throw unsafe("AUTH_DB_USERNAME must not use the local development default outside dev/test");
        }
    }

    private static void requireProductionPassword(String value) {
        if (value == null || value.isBlank()) {
            throw unsafe("AUTH_DB_PASSWORD is required outside dev/test");
        }
        if (LOCAL_PASSWORD.equals(value)) {
            throw unsafe("AUTH_DB_PASSWORD must not use the local development default outside dev/test");
        }
    }

    private static boolean isLoopback(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (LOOPBACK_HOSTS.contains(normalized)
                || normalized.endsWith(".localhost")
                || "localhost.localdomain".equals(normalized)
                || normalized.startsWith("127.")) {
            return true;
        }
        if (normalized.contains(":")
                || normalized.contains(".")
                || normalized.chars().allMatch(Character::isDigit)) {
            try {
                InetAddress address = InetAddress.getByName(normalized);
                return address.isLoopbackAddress() || address.isAnyLocalAddress();
            } catch (UnknownHostException ignored) {
                return false;
            }
        }
        return false;
    }

    private static IllegalStateException unsafe(String message) {
        return new IllegalStateException(message);
    }
}
