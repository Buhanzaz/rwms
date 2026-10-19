package dev.buhanzaz.rwms.taskboard.config;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Validated auth-service client settings used for worker-credential reconciliation.
 *
 * <p>These values describe a private service-to-service boundary and must never be replaced with a
 * public gateway route.
 */
@Validated
@ConfigurationProperties("rwms.auth")
public record TaskBoardClientProperties(
    @NotNull URI workerCredentialsUrl,
    @NotNull Duration connectTimeout,
    @NotNull Duration readTimeout,
    @NotNull Duration credentialOperationTimeout) {
  public TaskBoardClientProperties {
    if (credentialOperationTimeout != null
        && readTimeout != null
        && credentialOperationTimeout.compareTo(readTimeout) <= 0) {
      throw new IllegalArgumentException(
          "Credential operation timeout must be greater than auth read timeout");
    }
  }
}
