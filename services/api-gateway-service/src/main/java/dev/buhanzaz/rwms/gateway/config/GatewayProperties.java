package dev.buhanzaz.rwms.gateway.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Validated
@ConfigurationProperties("rwms.gateway")
public class GatewayProperties {

  @Valid private final Routes routes = new Routes();
  @Valid private final Security security = new Security();
  @Valid private final Cors cors = new Cors();
  @Valid private final AppLinks appLinks = new AppLinks();
  @Valid private final Sse sse = new Sse();
  @Setter
  @NotNull private URI publicBaseUri;

  @Getter
  @Setter
  public static final class Routes {
    @NotNull private URI authUri;
    @NotNull private URI taskBoardUri;
    @NotNull private URI warehouseUri;
    @NotNull private URI assetUri;
    @NotNull private URI maintenanceUri;
    @NotNull private URI mediaUri;
    @NotNull private URI inventoryUri;
    @NotNull private URI logisticsUri;
    @NotNull private URI dossierUri;
    @NotNull private URI analyticsUri;
    @NotNull private URI assistantUri;
  }

  @Getter
  @Setter
  public static final class Security {
    @NotBlank private String issuer;
    @NotBlank private String audience = "rwms-services";
  }

  @Setter
  @Getter
  public static final class Cors {
    @NotEmpty private List<String> allowedOrigins = List.of();

  }

  @Setter
  @Getter
  public static final class AppLinks {
    @NotBlank
    @Pattern(regexp = "^[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+$")
    private String packageName = "dev.buhanzaz.rwms.worker";

    private List<
            @Pattern(
                regexp =
                    "^(?:[0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}$",
                message = "must be a colon-separated SHA-256 certificate fingerprint")
            String>
        sha256CertFingerprints = List.of();
  }

  @Getter
  @Setter
  public static final class Sse {
    @Min(1)
    private int maxConnections = 128;

    @NotNull
    private Duration headerTimeout = Duration.ofSeconds(10);
  }
}
