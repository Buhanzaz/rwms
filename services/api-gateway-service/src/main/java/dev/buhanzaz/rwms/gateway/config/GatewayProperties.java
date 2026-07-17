package dev.buhanzaz.rwms.gateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.util.List;

@Getter
@Validated
@ConfigurationProperties("rwms.gateway")
public class GatewayProperties {

  @Valid private final Routes routes = new Routes();
  @Valid private final Security security = new Security();
  @Valid private final Cors cors = new Cors();
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
  }

  @Getter
  @Setter
  public static final class Security {
    @NotBlank private String issuer;
    @NotBlank private String audience = "rwms-services";
    @NotNull private URI jwkSetUri;
  }

  @Setter
  @Getter
  public static final class Cors {
    @NotEmpty private List<String> allowedOrigins = List.of();

  }
}
