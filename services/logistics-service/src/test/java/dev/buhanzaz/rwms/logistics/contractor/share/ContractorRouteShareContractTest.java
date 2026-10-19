package dev.buhanzaz.rwms.logistics.contractor.share;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Guards the canonical authenticated/public route-share and planner-identity contract surface. */
class ContractorRouteShareContractTest {
  @Test
  void contractPublishesExactSecurityLifecycleAndAllowlistedLiveStepContent() throws Exception {
    Map<String, Object> document = contract();
    Map<String, Object> paths = map(document.get("paths"));
    Map<String, Object> create =
        map(paths.get("/api/logistics/v1/warehouses/{warehouseId}/contractor-route-shares"));
    Map<String, Object> publicRead =
        map(paths.get("/api/logistics/public/v1/contractor-route-shares/{token}"));
    Map<String, Object> publicAction =
        map(
            paths.get(
                "/api/logistics/public/v1/contractor-route-shares/{token}/tasks/{externalTaskId}/entries/{entryId}/actions"));
    Map<String, Object> publicUpload =
        map(
            paths.get(
                "/api/logistics/public/v1/contractor-route-shares/{token}/tasks/{externalTaskId}/entries/{entryId}/evidence/{evidenceId}"));
    Map<String, Object> publicMedia =
        map(
            paths.get(
                "/api/logistics/public/v1/contractor-route-shares/{token}/tasks/{externalTaskId}/entries/{entryId}/media/{mediaId}/generations/{generation}/variants/{variant}/content"));

    assertThat(map(create.get("post")).get("security")).isNull();
    assertThat(map(publicRead.get("get")).get("security")).isEqualTo(List.of());
    assertThat(map(publicAction.get("post")).get("security")).isEqualTo(List.of());
    assertThat(map(publicUpload.get("post")).get("security")).isEqualTo(List.of());
    assertThat(map(publicMedia.get("get")).get("security")).isEqualTo(List.of());
    assertThat(list(map(publicUpload.get("post")).get("parameters")).toString())
        .contains("IdempotencyKey", "X-Content-SHA256", "X-Captured-At");
    assertThat(map(map(publicUpload.get("post")).get("requestBody")).toString())
        .contains("image/jpeg", "image/webp", "15728640", "1048576");
    assertThat(map(map(publicUpload.get("post")).get("responses")))
        .containsKeys("400", "404", "409", "411", "413", "415", "503");

    Map<String, Object> responses = map(map(document.get("components")).get("responses"));
    assertThat(responses).containsKeys("LengthRequired", "PayloadTooLarge");
    Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
    Map<String, Object> shareProperties =
        map(map(schemas.get("ContractorRouteShare")).get("properties"));
    assertThat(map(shareProperties.get("publicPath")).get("pattern"))
        .isEqualTo("^/contractor-routes/");

    Map<String, Object> routeEntry = map(schemas.get("PublicContractorRouteEntry"));
    assertThat(list(routeEntry.get("required")))
        .contains("works", "materials", "comments", "sourceMedia", "evidence", "completionAllowed");
    assertThat(map(routeEntry.get("properties"))).containsKeys("works", "evidence");
    assertThat(schemas)
        .containsKeys(
            "PublicContractorWork",
            "PublicContractorMaterial",
            "PublicContractorComment",
            "PublicContractorEvidence",
            "PublicContractorEvidenceUpload");
    Map<String, Object> sourceMediaProperties =
        map(map(schemas.get("PublicContractorSourceMedia")).get("properties"));
    assertThat(sourceMediaProperties).containsKeys("contentPath", "thumbnailPath");
    Map<String, Object> evidenceProperties =
        map(map(schemas.get("PublicContractorEvidence")).get("properties"));
    assertThat(evidenceProperties).containsKeys("contentPath", "thumbnailPath");
    assertThat(map(schemas.get("PublicContractorEvidence")).toString())
        .doesNotContain("reviewReason", "contentUrl", "uploadUrl", "objectKey");

    Map<String, Object> applied = map(schemas.get("AppliedPlanningAssignment"));
    assertThat(list(applied.get("required"))).contains("externalTaskId");
    assertThat(map(applied.get("properties"))).containsKey("externalTaskId");
  }

  private static Map<String, Object> contract() throws Exception {
    Path contract =
        Path.of(System.getProperty("rwms.contracts.dir"), "openapi/logistics-service.yaml");
    try (InputStream input = Files.newInputStream(contract)) {
      return map(new Yaml().load(input));
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    assertThat(value).isInstanceOf(Map.class);
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    assertThat(value).isInstanceOf(List.class);
    return (List<Object>) value;
  }
}
