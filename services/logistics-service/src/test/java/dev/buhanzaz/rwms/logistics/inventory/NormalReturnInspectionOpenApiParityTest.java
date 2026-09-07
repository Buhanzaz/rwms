package dev.buhanzaz.rwms.logistics.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Freezes the least-privilege completed normal-return inspection read contract. */
class NormalReturnInspectionOpenApiParityTest {

  @Test
  void contractExposesOnlyTerminalInspectionProofAndExternalMediaReferences() throws Exception {
    Map<String, Object> document = openApi();
    Map<String, Object> paths = child(document, "paths");
    Map<String, Object> operation =
        child(
            child(paths, "/api/internal/logistics/v1/inventory/returns/{returnId}/inspection"),
            "get");
    assertThat(operation.get("operationId")).isEqualTo("getCompletedNormalReturnInspection");

    Map<String, Object> schemas = child(child(document, "components"), "schemas");
    assertThat(list(child(schemas, "NormalReturnInspection").get("required")))
        .containsExactly(
            "returnId",
            "documentVersion",
            "warehouseId",
            "arrivedAt",
            "completedAt",
            "terminalState",
            "lines");
    assertThat(child(child(child(schemas, "NormalReturnInspection"), "properties"), "terminalState"))
        .containsEntry("enum", List.of("ACCEPTED", "ESTIMATE_REQUESTED"));
    assertThat(list(child(schemas, "NormalReturnInspectionLine").get("required")))
        .containsExactly("lineId", "assetId", "assetVersion", "status", "media");
    assertThat(list(child(schemas, "NormalReturnInspectionMedia").get("required")))
        .containsExactly("mediaId", "generation", "ownerType", "ownerVerifiedAt");
    assertThat(child(child(child(schemas, "NormalReturnInspectionMedia"), "properties"), "ownerType"))
        .containsEntry("enum", List.of("LOGISTICS_RETURN"));
    assertThat(child(child(schemas, "NormalReturnInspection"), "properties"))
        .doesNotContainKeys("passport", "passportSnapshot", "passportObservation");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> openApi() throws Exception {
    Path contract =
        Path.of(System.getProperty("rwms.contracts.dir"), "openapi/logistics-service.yaml");
    try (InputStream input = Files.newInputStream(contract)) {
      return new Yaml().load(input);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> child(Map<String, Object> map, String name) {
    return (Map<String, Object>) map.get(name);
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    return (List<Object>) value;
  }
}
