package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.domain.CredentialStatus;
import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventStore;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** Fail-closed authority for the reviewed old-panel task-board bootstrap. */
@Component
public class ReviewedTaskBoardManifest {
  static final String RESOURCE = "legacy/task-board-reviewed-bootstrap-v1.json";
  static final String SOURCE_SHA256 =
      "76f2a0c7c2527c61d700e4abfe46a06f314b666099889fb5c27dd8f746d6187e";
  static final UUID SPB_WAREHOUSE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");
  static final UUID MSK_WAREHOUSE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000002");

  private final Manifest manifest;

  public ReviewedTaskBoardManifest(ObjectMapper objectMapper) {
    byte[] bytes = readResource();
    if (!SOURCE_SHA256.equals(TaskBoardEventStore.sha256(bytes))) {
      throw new IllegalStateException("Packaged reviewed task-board manifest hash is invalid");
    }
    ObjectMapper strictMapper =
        objectMapper
            .rebuild()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    try {
      manifest = strictMapper.readValue(bytes, Manifest.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException(
          "Packaged reviewed task-board manifest has an invalid strict shape", exception);
    }
    validate();
  }

  public List<WorkerClassSeed> workerClasses() {
    return manifest.workerClasses();
  }

  public List<QueueSeed> queues(UUID warehouseId) {
    requireCanonicalWarehouse(warehouseId);
    return manifest.queues().stream()
        .filter(queue -> warehouseId.equals(queue.warehouseId()))
        .toList();
  }

  public List<WorkerSeed> workers(UUID warehouseId) {
    requireCanonicalWarehouse(warehouseId);
    return manifest.workers().stream()
        .filter(worker -> warehouseId.equals(worker.warehouseId()))
        .toList();
  }

  public List<GroupSeed> groups(UUID warehouseId) {
    requireCanonicalWarehouse(warehouseId);
    return manifest.groups().stream()
        .filter(group -> warehouseId.equals(group.warehouseId()))
        .toList();
  }

  public ManifestCounts counts(UUID warehouseId) {
    List<QueueSeed> queues = queues(warehouseId);
    List<WorkerSeed> workers = workers(warehouseId);
    List<GroupSeed> groups = groups(warehouseId);
    return new ManifestCounts(
        manifest.workerClasses().size(),
        queues.size(),
        (int) queues.stream().filter(queue -> queue.binding() != null).count(),
        workers.size(),
        workers.stream().mapToInt(worker -> worker.qualifications().size()).sum(),
        groups.size(),
        groups.stream().mapToInt(group -> group.members().size()).sum());
  }

  public String sourceSha256() {
    return SOURCE_SHA256;
  }

  public void requireCanonicalWarehouse(UUID warehouseId) {
    if (!SPB_WAREHOUSE_ID.equals(warehouseId) && !MSK_WAREHOUSE_ID.equals(warehouseId)) {
      throw new NotFoundException("Reviewed task-board bootstrap is unavailable for this warehouse");
    }
  }

  private byte[] readResource() {
    try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
      return input.readAllBytes();
    } catch (IOException exception) {
      throw new IllegalStateException(
          "Packaged reviewed task-board manifest cannot be loaded", exception);
    }
  }

  private void validate() {
    boolean valid =
        manifest != null
            && manifest.manifestVersion() == 1
            && Set.copyOf(manifest.warehouses())
                .equals(Set.of(SPB_WAREHOUSE_ID, MSK_WAREHOUSE_ID))
            && manifest.warehouses().size() == 2
            && manifest.workerClasses().size() == 7
            && manifest.queues().size() == 16
            && manifest.workers().size() == 18
            && manifest.groups().size() == 9
            && unique(manifest.workerClasses().stream().map(WorkerClassSeed::id).toList())
            && uniqueNormalized(manifest.workerClasses().stream().map(WorkerClassSeed::code).toList())
            && unique(manifest.queues().stream().map(QueueSeed::id).toList())
            && uniqueNormalized(
                manifest.queues().stream()
                    .map(queue -> queue.warehouseId() + ":" + queue.code())
                    .toList())
            && unique(manifest.workers().stream().map(WorkerSeed::id).toList())
            && uniqueNormalized(manifest.workers().stream().map(WorkerSeed::appLogin).toList())
            && unique(manifest.groups().stream().map(GroupSeed::id).toList())
            && unique(
                manifest.groups().stream()
                    .map(group -> group.warehouseId() + ":" + group.name())
                    .toList());
    if (!valid || !relationsAreValid()) {
      throw new IllegalStateException("Packaged reviewed task-board manifest is invalid");
    }
    ManifestCounts spb = counts(SPB_WAREHOUSE_ID);
    ManifestCounts msk = counts(MSK_WAREHOUSE_ID);
    if (!spb.equals(new ManifestCounts(7, 8, 7, 18, 22, 9, 18))
        || !msk.equals(new ManifestCounts(7, 8, 7, 0, 0, 0, 0))) {
      throw new IllegalStateException("Packaged reviewed task-board manifest counts are invalid");
    }
  }

  private boolean relationsAreValid() {
    Set<UUID> classIds =
        manifest.workerClasses().stream()
            .map(WorkerClassSeed::id)
            .collect(java.util.stream.Collectors.toSet());
    Set<UUID> workerIds =
        manifest.workers().stream()
            .map(WorkerSeed::id)
            .collect(java.util.stream.Collectors.toSet());
    boolean queuesValid =
        manifest.queues().stream()
            .allMatch(
                queue ->
                    manifest.warehouses().contains(queue.warehouseId())
                        && queue.id() != null
                        && text(queue.code())
                        && text(queue.name())
                        && queue.type() != null
                        && queue.sortOrder() > 0
                        && (queue.binding() == null
                            || classIds.contains(queue.binding().workerClassId())));
    boolean workersValid =
        manifest.workers().stream()
            .allMatch(
                worker ->
                    SPB_WAREHOUSE_ID.equals(worker.warehouseId())
                        && worker.id() != null
                        && text(worker.displayName())
                        && text(worker.firstName())
                        && text(worker.lastName())
                        && text(worker.appLogin())
                        && worker.credentialStatus() == CredentialStatus.NOT_CONFIGURED
                        && !worker.qualifications().isEmpty()
                        && unique(worker.qualifications())
                        && classIds.containsAll(worker.qualifications()));
    boolean groupsValid =
        manifest.groups().stream()
            .allMatch(
                group ->
                    SPB_WAREHOUSE_ID.equals(group.warehouseId())
                        && group.id() != null
                        && classIds.contains(group.workerClassId())
                        && text(group.name())
                        && group.members().size() == 2
                        && unique(group.members())
                        && workerIds.containsAll(group.members()));
    return queuesValid && workersValid && groupsValid;
  }

  private static boolean text(String value) {
    return value != null && !value.isBlank();
  }

  private static boolean unique(List<?> values) {
    return values.stream().noneMatch(java.util.Objects::isNull)
        && new HashSet<>(values).size() == values.size();
  }

  private static boolean uniqueNormalized(List<String> values) {
    return values.stream().noneMatch(value -> value == null || value.isBlank())
        && values.stream().map(value -> value.toLowerCase(Locale.ROOT)).distinct().count()
            == values.size();
  }

  public record ManifestCounts(
      int workerClasses,
      int workQueues,
      int queueBindings,
      int workers,
      int qualifications,
      int workerGroups,
      int memberships) {
    public int total() {
      return workerClasses
          + workQueues
          + queueBindings
          + workers
          + qualifications
          + workerGroups
          + memberships;
    }
  }

  public record WorkerClassSeed(
      UUID id,
      String code,
      String name,
      String description,
      String comment,
      int sortOrder,
      boolean active) {}

  public record QueueBindingSeed(UUID workerClassId, boolean stopTaskOnTake) {}

  public record QueueSeed(
      UUID id,
      UUID warehouseId,
      String code,
      String name,
      String description,
      QueueType type,
      int sortOrder,
      boolean active,
      boolean hidden,
      boolean collapsed,
      Integer holdingPeriodMinutes,
      Integer notificationThreshold,
      boolean notifyWhenThresholdReached,
      QueueBindingSeed binding) {}

  public record WorkerSeed(
      UUID id,
      UUID warehouseId,
      String displayName,
      String firstName,
      String lastName,
      String middleName,
      boolean active,
      String comment,
      String appLogin,
      CredentialStatus credentialStatus,
      List<UUID> qualifications) {
    public WorkerSeed {
      qualifications = List.copyOf(qualifications);
    }
  }

  public record GroupSeed(
      UUID id,
      UUID warehouseId,
      UUID workerClassId,
      String name,
      String description,
      boolean active,
      List<UUID> members) {
    public GroupSeed {
      members = List.copyOf(members);
    }
  }

  private record Manifest(
      int manifestVersion,
      List<UUID> warehouses,
      List<WorkerClassSeed> workerClasses,
      List<QueueSeed> queues,
      List<WorkerSeed> workers,
      List<GroupSeed> groups) {
    private Manifest {
      warehouses = List.copyOf(warehouses);
      workerClasses = List.copyOf(workerClasses);
      queues = List.copyOf(queues);
      workers = List.copyOf(workers);
      groups = List.copyOf(groups);
    }
  }
}
