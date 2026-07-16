package dev.buhanzaz.rwms.taskboard.config;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

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
