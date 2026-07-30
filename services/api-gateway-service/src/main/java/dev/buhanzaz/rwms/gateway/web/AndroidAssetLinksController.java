package dev.buhanzaz.rwms.gateway.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.buhanzaz.rwms.gateway.config.GatewayProperties;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AndroidAssetLinksController {

  private static final List<String> RELATIONS =
      List.of("delegate_permission/common.handle_all_urls");

  private final GatewayProperties properties;

  public AndroidAssetLinksController(GatewayProperties properties) {
    this.properties = properties;
  }

  @GetMapping(
      path = "/.well-known/assetlinks.json",
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<List<AssetLinkStatement>> assetLinks() {
    var appLinks = properties.getAppLinks();
    if (appLinks.getSha256CertFingerprints().isEmpty()) {
      return ResponseEntity.status(503)
          .cacheControl(CacheControl.noStore())
          .body(List.of());
    }
    var target =
        new AndroidAppTarget(
            "android_app",
            appLinks.getPackageName(),
            appLinks.getSha256CertFingerprints().stream()
                .map(value -> value.toUpperCase(Locale.ROOT))
                .distinct()
                .toList());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic().noTransform())
        .body(List.of(new AssetLinkStatement(RELATIONS, target)));
  }

  record AssetLinkStatement(List<String> relation, AndroidAppTarget target) {}

  record AndroidAppTarget(
      String namespace,
      @JsonProperty("package_name") String packageName,
      @JsonProperty("sha256_cert_fingerprints") List<String> sha256CertFingerprints) {}
}
