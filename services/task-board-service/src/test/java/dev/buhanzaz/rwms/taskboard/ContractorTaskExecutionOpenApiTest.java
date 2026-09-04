package dev.buhanzaz.rwms.taskboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Verifies the canonical private contractor contract remains narrow and version-fenced. */
class ContractorTaskExecutionOpenApiTest {
  @Test
  void contractDeclaresExactSnapshotActionsAndEvidenceWithoutContactOrBearerPaths()
      throws Exception {
    Map<String, Object> contract = contract();
    Map<String, Object> paths = child(contract, "paths");
    String taskPath =
        "/internal/task-board/v1/logistics/contractor-execution/workers/{workerId}/tasks/{externalTaskId}";
    String actionPath = taskPath + "/entries/{entryId}/actions";
    String evidencePath = taskPath + "/entries/{entryId}/evidence-reservations";
    assertThat(paths).containsKeys(taskPath, actionPath, evidencePath);
    assertThat(child(paths, actionPath).get("parameters").toString())
        .contains("WorkerIdempotencyKey", "EntryId", "ExternalTaskId");
    assertThat(child(paths, evidencePath).get("parameters").toString())
        .contains("WorkerIdempotencyKey", "EntryId", "ExternalTaskId");
    assertThat(child(child(paths, evidencePath), "post").toString())
        .contains("ContractorEvidenceReservationRequest", "ContractorEvidenceReservation")
        .doesNotContain("offlineLeaseId", "uploadPath", "readPath", "thumbnailPath");

    Map<String, Object> schemas = child(child(contract, "components"), "schemas");
    Map<String, Object> snapshot = child(schemas, "ContractorTaskExecutionSnapshot");
    Map<String, Object> route = child(schemas, "ContractorTaskRouteEntry");
    Map<String, Object> taskEvidence = child(schemas, "ContractorTaskEvidence");
    Map<String, Object> action = child(schemas, "ContractorTaskActionRequest");
    Map<String, Object> result = child(schemas, "ContractorTaskActionResult");
    Map<String, Object> evidenceRequest =
        child(schemas, "ContractorEvidenceReservationRequest");
    Map<String, Object> evidence = child(schemas, "ContractorEvidenceReservation");
    assertThat(snapshot.get("required").toString())
        .contains("workerId", "externalTaskId", "unitNumber", "source", "route");
    assertThat(snapshot.toString()).doesNotContain("phone", "credential");
    assertThat(route.get("required").toString())
        .contains(
            "version",
            "routeIndex",
            "routeStepIndex",
            "taskText",
            "works",
            "materials",
            "comments",
            "sourceMedia",
            "resultPhotoMinCount",
            "evidence",
            "completionAllowed");
    assertThat(route.toString()).doesNotContain("readPath", "thumbnailPath");
    assertThat(child(child(route, "properties"), "evidence")).containsEntry("maxItems", 100);
    assertThat(taskEvidence.get("required").toString())
        .contains(
            "evidenceId",
            "version",
            "capturedAt",
            "recordedAt",
            "state",
            "mediaId",
            "mediaGeneration",
            "reviewReason",
            "contentType");
    assertThat(taskEvidence.toString()).contains("RESERVED", "READY", "REVIEW_REQUIRED");
    assertThat(child(taskEvidence, "properties"))
        .doesNotContainKeys("readPath", "thumbnailPath", "uploadPath", "bearerToken");
    assertThat(child(action, "properties").toString())
        .contains("START", "COMPLETE", "expectedVersion", "evidenceId");
    assertThat(child(result, "properties").toString())
        .contains("currentVersion", "ContractorTaskExecutionSnapshot");
    assertThat(evidenceRequest.get("required").toString())
        .contains("operationId", "evidenceId", "capturedAt", "contentType", "sizeBytes", "sha256")
        .doesNotContain("routeIndex", "warehouseId", "workerId", "offlineLeaseId");
    assertThat(evidenceRequest.toString())
        .contains("image/jpeg", "image/webp", "15728640", "1048576");
    assertThat(evidence.get("required").toString())
        .contains(
            "evidenceId",
            "version",
            "state",
            "entryId",
            "ownerType",
            "ownerId",
            "warehouseId",
            "clientReferenceId",
            "capturedAt",
            "contentType",
            "sizeBytes",
            "sha256");
    assertThat(evidence.toString())
        .contains("TASK_BOARD_ENTRY", "RESERVED", "READY")
        .doesNotContain("phone", "offlineLeaseId", "uploadPath", "readPath", "thumbnailPath");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> contract() throws Exception {
    Path path =
        Path.of(
            System.getProperty("rwms.contracts.dir"),
            "openapi/task-board-service.yaml");
    try (InputStream input = Files.newInputStream(path)) {
      return new Yaml().load(input);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> child(Map<String, Object> value, String key) {
    return (Map<String, Object>) value.get(key);
  }
}
