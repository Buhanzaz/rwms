package dev.buhanzaz.rwms.logistics.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Freezes the canonical disposition request, result and public historical-document read shapes. */
class InventoryOutcomeOpenApiParityTest {

  @Test
  void canonicalContractCarriesEveryDispositionFactAndNullableWorkflowPayload() throws Exception {
    Map<String, Object> schemas = child(child(openApi(), "components"), "schemas");
    Map<String, Object> outcome = child(schemas, "InventoryAssetOutcome");
    assertThat(list(outcome.get("required")))
        .containsExactly(
            "findingId",
            "assetId",
            "dispositionKind",
            "desiredStatus",
            "formerRental",
            "shipment");
    assertThat(list(child(schemas, "InventoryDispositionKind").get("enum")))
        .containsExactly("LOCAL", "SHIPMENT", "WRITE_OFF");
    assertThat(child(child(outcome, "properties"), "desiredStatus").toString())
        .contains("FREE", "REPAIR", "CAPITAL_REPAIR", "RENTED", "WRITE_OFF_PENDING");
    assertThat(child(child(outcome, "properties"), "formerRental").toString())
        .contains("InventoryFormerRental", "type=null");
    assertThat(child(child(outcome, "properties"), "shipment").toString())
        .contains("InventoryShipment", "type=null");

    Map<String, Object> shipment = child(schemas, "InventoryShipment");
    assertThat(list(shipment.get("required")))
        .containsExactly("departedOn", "clientId", "clientSnapshot", "furniture");
    assertThat(child(child(shipment, "properties"), "furniture").toString())
        .contains("maxItems=100", "InventoryShipmentFurniture");

    Map<String, Object> result = child(schemas, "InventoryDispositionResult");
    assertThat(list(result.get("required")))
        .containsExactly(
            "findingId", "assetId", "dispositionKind", "markerId", "documentId", "lineId");
    assertThat(child(child(result, "properties"), "documentId").get("type"))
        .isEqualTo(List.of("string", "null"));
    assertThat(list(child(schemas, "ApplyInventoryOutcomeResponse").get("required")))
        .contains("dispositions");

    Map<String, Object> publicDocument = child(schemas, "LogisticsDocument");
    assertThat(list(publicDocument.get("required")))
        .contains(
            "inventorySourceId",
            "inventorySourceFindingId",
            "inventorySourceDispositionKind");
    assertThat(list(child(schemas, "LogisticsLine").get("required")))
        .contains("inventoryShipmentFurniture");
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
