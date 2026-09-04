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

/**
 * Validated configuration contract for the public gateway.
 *
 * <p>Downstream route values are private service origins, while {@link #publicBaseUri} is the
 * single browser-visible origin used to derive the public auth issuer and trusted forwarding
 * metadata. Keeping those identities separate prevents accidental routing loops and trust of
 * client-controlled forwarding headers.
 */
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

  /** Private HTTP origins for the services exposed through the public gateway. */
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
    @NotNull private URI logisticsPlannerUri;
    @NotNull private URI dossierUri;
    @NotNull private URI analyticsUri;
    @NotNull private URI assistantUri;
  }

  /** JWT issuer and audience accepted at the public edge. */
  @Getter
  @Setter
  public static final class Security {
    @NotBlank private String issuer;
    @NotBlank private String audience = "rwms-services";
  }

  /** Browser origins explicitly allowed to call the public gateway. */
  @Setter
  @Getter
  public static final class Cors {
    @NotEmpty private List<String> allowedOrigins = List.of();

  }

  /** Android App Links identity published from {@code /.well-known/assetlinks.json}. */
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

  /** Resource limits for long-lived server-sent event proxy connections. */
  @Getter
  @Setter
  public static final class Sse {
    @Min(1)
    private int maxConnections = 128;

    @NotNull
    private Duration headerTimeout = Duration.ofSeconds(10);
  }
}
