package dev.buhanzaz.rwms.taskboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Locks the narrow inventory calendar boundary to its explicit private transport shape. */
class InventoryWorkCalendarContractTest {

  @Test
  void calendarReadIsPrivateAndCarriesEffectiveScheduleAndTimezoneEvidence() throws Exception {
    Map<String, Object> document = document();
    Map<String, Object> operation =
        map(
            map(paths(document).get(
                    "/internal/task-board/v1/inventory/warehouses/{warehouseId}/work-calendar"))
                .get("get"));

    assertThat(String.valueOf(operation.get("description")))
        .contains("inventory-service SERVICE principal")
        .contains("task-board.inventory-calendar.read");
    assertThat(list(operation.get("parameters")))
        .extracting(value -> String.valueOf(map(value).get("name")))
        .containsExactly("from", "through");
    assertThat(map(operation.get("responses")))
        .containsEntry("200", Map.of("$ref", "#/components/responses/InventoryWorkCalendarSnapshotOk"))
        .containsKeys("400", "401", "403");

    Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
    Map<String, Object> snapshot = map(schemas.get("InventoryWorkCalendarSnapshot"));
    assertThat(list(snapshot.get("required")))
        .containsExactly("warehouseId", "from", "through", "calendarFingerprint", "dates");
    assertThat(map(snapshot.get("properties")))
        .containsKeys("warehouseId", "from", "through", "calendarFingerprint", "dates");

    Map<String, Object> date = map(schemas.get("InventoryWorkCalendarDate"));
    assertThat(list(date.get("required")))
        .containsExactly(
            "date",
            "working",
            "timeZone",
            "timeZoneEffectiveFrom",
            "scheduleId",
            "scheduleVersion",
            "scheduleEffectiveFrom");
    assertThat(map(date.get("properties")))
        .containsKeys(
            "date",
            "working",
            "timeZone",
            "timeZoneEffectiveFrom",
            "scheduleId",
            "scheduleVersion",
            "scheduleEffectiveFrom");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> document() throws Exception {
    Path contract =
        Path.of(System.getProperty("rwms.contracts.dir"), "openapi/task-board-service.yaml");
    try (InputStream input = Files.newInputStream(contract)) {
      return (Map<String, Object>) new Yaml().load(input);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> paths(Map<String, Object> document) {
    return (Map<String, Object>) document.get("paths");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    return (List<Object>) value;
  }
}
