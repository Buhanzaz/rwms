package dev.buhanzaz.rwms.logistics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Pins the owner-side commitment, base-work, preview, and retention contract shapes. */
class PlanningCommitmentOpenApiContractTest {
  @Test
  void planningAndAdministratorBoundariesExposeTheirRequiredFences() throws Exception {
    Map<String, Object> document = openApi();
    Map<String, Object> paths = child(document, "paths");
    assertThat(paths)
        .containsKeys(
            "/api/internal/logistics/v1/planning/orders/{orderId}/reschedule-options",
            "/api/internal/logistics/v1/planning/orders/{orderId}/reschedule",
            "/api/internal/logistics/v1/planning/assignments/{sourcePlanId}/withdraw-cancelled",
            "/api/internal/logistics/v1/planning/base-tasks",
            "/api/internal/logistics/v1/planning/assignments/{externalTaskId}/provisional-eta",
            "/api/logistics/v1/retention/dry-run",
            "/api/logistics/v1/retention/legal-holds",
            "/api/logistics/v1/retention/legal-holds/{holdId}/release",
            "/api/logistics/v1/retention/archive-manifests",
            "/api/logistics/v1/retention/archive-manifests/{manifestId}/verify")
        .doesNotContainKey("/api/internal/logistics/v1/planning/retention/dry-run");

    Map<String, Object> optionGet =
        child(
            child(paths, "/api/internal/logistics/v1/planning/orders/{orderId}/reschedule-options"),
            "get");
    List<Map<String, Object>> parameters = list(optionGet, "parameters");
    assertThat(parameters)
        .extracting(value -> value.get("name"))
        .containsExactly("orderId", "expectedOrderVersion");
    Map<String, Object> cancellationPost =
        child(
            child(
                paths,
                "/api/internal/logistics/v1/planning/assignments/{sourcePlanId}/withdraw-cancelled"),
            "post");
    List<Map<String, Object>> cancellationParameters = list(cancellationPost, "parameters");
    assertThat(cancellationParameters)
        .anySatisfy(parameter -> assertThat(parameter).containsEntry("name", "sourcePlanId"))
        .anySatisfy(
            parameter ->
                assertThat(parameter)
                    .containsEntry("$ref", "#/components/parameters/IdempotencyKey"));
    assertThat(
            child(
                    child(
                        child(child(cancellationPost, "requestBody"), "content"),
                        "application/json"),
                    "schema")
                .get("$ref"))
        .isEqualTo("#/components/schemas/PlanningPublishedAssignmentWithdrawal");

    Map<String, Object> schemas = child(child(document, "components"), "schemas");
    assertThat(list(child(schemas, "ClientType"), "enum"))
        .containsExactly("INDIVIDUAL", "SOLE_PROPRIETOR", "LEGAL_ENTITY");
    assertThat(list(child(schemas, "PlanningRequest"), "required"))
        .contains("clientType", "contactName", "contactPhone");
    assertThat(list(child(schemas, "PlanningOrderRescheduleRequest"), "required"))
        .contains(
            "expectedOrderVersion",
            "expectedSessionVersion",
            "slotId",
            "slotVersion",
            "decisionCode",
            "decisionActorSubjectId",
            "decisionReason");
    assertThat(list(child(schemas, "AppliedPlanningAssignment"), "required"))
        .contains("externalTaskId", "taskVersion");
    assertThat(list(child(schemas, "PlanningAssignmentStatus"), "required"))
        .contains("externalTaskId", "taskVersion");
    assertThat(list(child(schemas, "DriverTripDetails"), "required"))
        .contains(
            "clientType",
            "provisionalEta",
            "provisionalEtaApproximate",
            "provisionalEtaSourcePlanId",
            "provisionalEtaSourcePlanVersion");
    assertThat(child(child(schemas, "PlacePlanningRetentionLegalHoldRequest"), "properties"))
        .doesNotContainKey("actorSubjectId");
    assertThat(child(child(schemas, "ReleasePlanningRetentionLegalHoldRequest"), "properties"))
        .doesNotContainKey("actorSubjectId");
    assertThat(child(child(schemas, "RecordPlanningArchiveManifestRequest"), "properties"))
        .doesNotContainKey("actorSubjectId");
    assertThat(
            child(
                    child(child(schemas, "RecordPlanningArchiveManifestRequest"), "properties"),
                    "objectKey")
                .get("writeOnly"))
        .isEqualTo(true);
    assertThat(child(child(schemas, "VerifyPlanningArchiveManifestRequest"), "properties"))
        .doesNotContainKey("actorSubjectId");
    assertThat(child(schemas, "PlanningArchiveManifest").toString()).doesNotContain("objectKey");
  }

  private static Map<String, Object> openApi() throws Exception {
    Path contract =
        Path.of(System.getProperty("rwms.contracts.dir"), "openapi/logistics-service.yaml");
    try (InputStream input = Files.newInputStream(contract)) {
      return new Yaml().load(input);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> child(Map<String, Object> map, String key) {
    return (Map<String, Object>) map.get(key);
  }

  @SuppressWarnings("unchecked")
  private static <T> List<T> list(Map<String, Object> map, String key) {
    return (List<T>) map.get(key);
  }
}
