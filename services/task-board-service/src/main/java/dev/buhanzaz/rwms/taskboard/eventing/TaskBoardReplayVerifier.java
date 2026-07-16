package dev.buhanzaz.rwms.taskboard.eventing;

import dev.buhanzaz.rwms.taskboard.repository.BoardTaskRepository;
import dev.buhanzaz.rwms.taskboard.repository.QueueEntryRepository;
import dev.buhanzaz.rwms.taskboard.repository.QueueUsageReferenceRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkQueueRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerClassRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerGroupRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class TaskBoardReplayVerifier {
  static final String REPLAY_PROJECTION = "task-board-replay-shadow-v1";
  private static final Set<String> TERMINAL_DELETE_EVENTS =
      Set.of(
          TaskBoardEventTypes.WORKER_CLASS_DELETED,
          TaskBoardEventTypes.WORKER_DELETED,
          TaskBoardEventTypes.WORKER_GROUP_DELETED,
          TaskBoardEventTypes.WORK_QUEUE_DELETED,
          TaskBoardEventTypes.QUEUE_REFERENCE_DELETED);

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final TaskBoardEventPayloadPolicy payloadPolicy;
  private final TaskBoardEventFactFactory facts;
  private final WorkerClassRepository workerClasses;
  private final WorkerRepository workers;
  private final WorkerGroupRepository workerGroups;
  private final WorkQueueRepository workQueues;
  private final QueueUsageReferenceRepository queueUsageReferences;
  private final BoardTaskRepository boardTasks;
  private final QueueEntryRepository queueEntries;
  private final TaskBoardReplayAuditStore replayAudit;
  private final TaskBoardEventingMetrics metrics;

  @Transactional(readOnly = true)
  public TaskBoardReplayAuditStore.Summary authoritativeSummary() {
    return summary(loadStreamIdentities(false));
  }

  @Transactional(readOnly = true)
  public ReplayResult verify(TaskBoardAggregateType aggregateType, UUID aggregateId) {
    StreamIdentity stream =
        loadStreamIdentities(false).stream()
            .filter(
                candidate ->
                    candidate.aggregateType() == aggregateType
                        && candidate.aggregateId().equals(aggregateId))
            .findFirst()
            .orElseThrow(
                () -> new IllegalStateException("Task-board authoritative stream does not exist"));
    return verifyAuthoritativeStream(stream);
  }

  @Transactional
  public ReplayParityResult rebuildShadowProjection(
      TaskBoardReplayAuditStore.Operation operation) {
    List<StreamIdentity> streams = loadStreamIdentities(true);
    var before = summary(streams);
    if (!before.equals(operation.before())) {
      throw new IllegalStateException("Task-board event streams changed after replay audit started");
    }
    requireNoBlockedAggregates();
    requireCanonicalOutboxCoverage();
    requireNoOrphanedShadowRows();

    StringBuilder parityMaterial = new StringBuilder();
    for (StreamIdentity stream : streams) {
      ReplayResult replay = verifyAuthoritativeStream(stream);
      jdbc.update(
          """
          insert into projection_checkpoint(
            projection_name,aggregate_type,aggregate_id,
            aggregate_version,projection_sha256,updated_at)
          values (?,?,?,?,?,clock_timestamp())
          on conflict (projection_name,aggregate_type,aggregate_id) do update
            set aggregate_version=excluded.aggregate_version,
                projection_sha256=excluded.projection_sha256,
                updated_at=excluded.updated_at
          """,
          REPLAY_PROJECTION,
          stream.aggregateType().name(),
          stream.aggregateId().toString(),
          replay.version(),
          replay.payloadSha256());
      parityMaterial
          .append(stream.aggregateType().name())
          .append('\u001f')
          .append(stream.aggregateId())
          .append('\u001f')
          .append(replay.version())
          .append('\u001f')
          .append(replay.payloadSha256())
          .append('\n');
    }
    assertShadowParity(streams.size());
    var result =
        new ReplayParityResult(
            streams.size(),
            streams.stream().mapToLong(StreamIdentity::version).sum(),
            TaskBoardEventStore.sha256(
                parityMaterial.toString().getBytes(StandardCharsets.UTF_8)));
    replayAudit.complete(
        operation.operationId(),
        new TaskBoardReplayAuditStore.Summary(
            result.aggregateCount(), result.versionSum(), result.canonicalChecksum()));
    return result;
  }

  private ReplayResult verifyAuthoritativeStream(StreamIdentity stream) {
    metrics.replayAttempted();
    try {
      List<StoredFact> stored =
          jdbc.query(
              """
              select event_id,aggregate_version,event_type,payload::text,payload_sha256,baseline
                from domain_event
               where aggregate_type=? and aggregate_id=?
               order by aggregate_version
              """,
              (result, row) ->
                  new StoredFact(
                      result.getObject("event_id", UUID.class),
                      result.getLong("aggregate_version"),
                      result.getString("event_type"),
                      result.getString("payload"),
                      result.getString("payload_sha256").trim(),
                      result.getBoolean("baseline")),
              stream.aggregateType().name(),
              stream.aggregateId().toString());
      if (stored.isEmpty()) throw new IllegalStateException("Task-board event stream has no facts");
      long previous = stored.getFirst().baseline() ? stored.getFirst().version() : -1;
      if (!stored.getFirst().baseline() && stored.getFirst().version() != 0) {
        throw new IllegalStateException("New task-board stream must start at version zero");
      }
      for (int index = 0; index < stored.size(); index++) {
        StoredFact fact = stored.get(index);
        if (index > 0 && fact.version() != previous + 1) {
          throw new IllegalStateException("Task-board event stream contains a version gap");
        }
        JsonNode payload = read(fact.payload());
        String validationType =
            fact.baseline() ? baselineValidationType(stream.aggregateType()) : fact.eventType();
        payloadPolicy.validateNode(
            validationType, stream.aggregateType(), stream.aggregateId(), payload);
        if (!TaskBoardEventStore.sha256(fact.payload().getBytes(StandardCharsets.UTF_8))
            .equals(fact.payloadSha256())) {
          throw new IllegalStateException("Task-board event payload checksum mismatch");
        }
        previous = fact.version();
      }
      StoredFact tail = stored.getLast();
      if (stream.version() != tail.version() || !stream.eventId().equals(tail.eventId())) {
        throw new IllegalStateException("Task-board stream head does not match replay tail");
      }
      Optional<Object> liveProjection = liveProjection(stream.aggregateType(), stream.aggregateId());
      if (liveProjection.isPresent()) {
        String projectionJson = canonicalJson(write(liveProjection.orElseThrow()));
        if (!projectionJson.equals(tail.payload())) {
          throw new IllegalStateException("Task-board replay tail does not match live projection");
        }
      } else if (!TERMINAL_DELETE_EVENTS.contains(tail.eventType())) {
        throw new IllegalStateException("Task-board live projection is missing without terminal delete");
      }
      return new ReplayResult(stream.version(), stored.size(), tail.payloadSha256());
    } catch (RuntimeException exception) {
      metrics.replayFailed();
      throw exception;
    }
  }

  private Optional<Object> liveProjection(TaskBoardAggregateType type, UUID id) {
    return switch (type) {
      case WORKER_CLASS ->
          workerClasses.findById(id).map(value -> (Object) facts.workerClass(value, false));
      case WORKER -> workers.findById(id).map(value -> (Object) facts.worker(value, false));
      case WORKER_GROUP ->
          workerGroups.findById(id).map(value -> (Object) facts.workerGroup(value, false));
      case WORK_QUEUE ->
          workQueues.findById(id).map(value -> (Object) facts.workQueue(value, false));
      case QUEUE_USAGE_REFERENCE ->
          queueUsageReferences
              .findById(id)
              .map(value -> (Object) facts.queueUsageReference(value, false));
      case BOARD_TASK ->
          boardTasks.findById(id).map(value -> (Object) facts.boardTask(value, false));
      case QUEUE_ENTRY ->
          queueEntries.findById(id).map(value -> (Object) facts.queueEntry(value, false));
    };
  }

  private TaskBoardReplayAuditStore.Summary summary(List<StreamIdentity> streams) {
    StringBuilder material = new StringBuilder();
    for (StreamIdentity stream : streams) {
      String tailHash = tailHash(stream);
      material
          .append(stream.aggregateType().name())
          .append('\u001f')
          .append(stream.aggregateId())
          .append('\u001f')
          .append(stream.version())
          .append('\u001f')
          .append(tailHash)
          .append('\n');
    }
    return new TaskBoardReplayAuditStore.Summary(
        streams.size(),
        streams.stream().mapToLong(StreamIdentity::version).sum(),
        TaskBoardEventStore.sha256(material.toString().getBytes(StandardCharsets.UTF_8)));
  }

  private List<StreamIdentity> loadStreamIdentities(boolean forUpdate) {
    return jdbc.query(
        """
        select aggregate_type,aggregate_id,current_version,last_event_id
          from event_stream_head order by aggregate_type,aggregate_id
        """
            + (forUpdate ? " for update" : ""),
        (result, row) ->
            new StreamIdentity(
                TaskBoardAggregateType.valueOf(result.getString("aggregate_type")),
                UUID.fromString(result.getString("aggregate_id")),
                result.getLong("current_version"),
                result.getObject("last_event_id", UUID.class)));
  }

  private String tailHash(StreamIdentity stream) {
    return jdbc.query(
            """
            select payload_sha256 from domain_event
             where aggregate_type=? and aggregate_id=?
               and aggregate_version=? and event_id=?
            """,
            (result, row) -> result.getString("payload_sha256").trim(),
            stream.aggregateType().name(),
            stream.aggregateId().toString(),
            stream.version(),
            stream.eventId())
        .stream()
        .findFirst()
        .orElseThrow(
            () -> new IllegalStateException("Task-board stream head has no canonical tail"));
  }

  private void requireCanonicalOutboxCoverage() {
    Integer invalid =
        jdbc.queryForObject(
            """
            select count(*) from domain_event event
             where not event.baseline and not exists (
               select 1 from outbox_event outbox
                where outbox.event_id=event.event_id
                  and outbox.aggregate_type=event.aggregate_type
                  and outbox.aggregate_id=event.aggregate_id
                  and outbox.aggregate_version=event.aggregate_version
                  and outbox.event_type=event.event_type
                  and outbox.envelope_sha256=encode(
                    sha256(convert_to(outbox.envelope_body::text,'UTF8')),'hex')
                  and event.payload_sha256=encode(
                    sha256(convert_to(event.payload::text,'UTF8')),'hex')
                  and outbox.envelope_body->>'producer'='task-board-service'
                  and outbox.envelope_body->'payload'=event.payload)
            """,
            Integer.class);
    if (invalid == null || invalid > 0) {
      throw new IllegalStateException(
          "Task-board replay corruption: non-baseline fact has no canonical outbox");
    }
  }

  private void requireNoBlockedAggregates() {
    Integer blocked =
        jdbc.queryForObject(
            "select count(*) from consumer_aggregate_checkpoint where blocked", Integer.class);
    if (blocked != null && blocked > 0) {
      throw new IllegalStateException("Task-board replay has blocked aggregates");
    }
  }

  private void requireNoOrphanedShadowRows() {
    Integer orphaned =
        jdbc.queryForObject(
            """
            select count(*) from projection_checkpoint checkpoint
              left join event_stream_head head
                on head.aggregate_type=checkpoint.aggregate_type
               and head.aggregate_id=checkpoint.aggregate_id
             where checkpoint.projection_name=? and head.aggregate_id is null
            """,
            Integer.class,
            REPLAY_PROJECTION);
    if (orphaned != null && orphaned > 0) {
      throw new IllegalStateException("Task-board replay projection contains an unknown aggregate");
    }
  }

  private void assertShadowParity(int expectedCount) {
    Integer matching =
        jdbc.queryForObject(
            """
            select count(*) from projection_checkpoint shadow
              join event_stream_head head
                on head.aggregate_type=shadow.aggregate_type
               and head.aggregate_id=shadow.aggregate_id
              join domain_event tail
                on tail.aggregate_type=head.aggregate_type
               and tail.aggregate_id=head.aggregate_id
               and tail.aggregate_version=head.current_version
               and tail.event_id=head.last_event_id
             where shadow.projection_name=?
               and shadow.aggregate_version=head.current_version
               and shadow.projection_sha256=tail.payload_sha256
            """,
            Integer.class,
            REPLAY_PROJECTION);
    Integer shadowCount =
        jdbc.queryForObject(
            "select count(*) from projection_checkpoint where projection_name=?",
            Integer.class,
            REPLAY_PROJECTION);
    if (matching == null
        || matching != expectedCount
        || shadowCount == null
        || shadowCount != expectedCount) {
      throw new IllegalStateException("Task-board shadow replay parity mismatch");
    }
  }

  private String baselineValidationType(TaskBoardAggregateType type) {
    return switch (type) {
      case WORKER_CLASS -> TaskBoardEventTypes.WORKER_CLASS_CREATED;
      case WORKER -> TaskBoardEventTypes.WORKER_CREATED;
      case WORKER_GROUP -> TaskBoardEventTypes.WORKER_GROUP_CREATED;
      case WORK_QUEUE -> TaskBoardEventTypes.WORK_QUEUE_CREATED;
      case QUEUE_USAGE_REFERENCE -> TaskBoardEventTypes.QUEUE_REFERENCE_CREATED;
      case BOARD_TASK -> TaskBoardEventTypes.BOARD_TASK_CREATED;
      case QUEUE_ENTRY -> TaskBoardEventTypes.QUEUE_ENTRY_CREATED;
    };
  }

  private JsonNode read(String value) {
    try {
      return objectMapper.readTree(value);
    } catch (tools.jackson.core.JacksonException exception) {
      throw new IllegalStateException("Stored task-board event payload is invalid JSON");
    }
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (tools.jackson.core.JacksonException exception) {
      throw new IllegalStateException("Task-board live projection cannot be serialized");
    }
  }

  private String canonicalJson(String value) {
    String canonical = jdbc.queryForObject("select (?::jsonb)::text", String.class, value);
    if (canonical == null) {
      throw new IllegalStateException("PostgreSQL did not canonicalize task-board replay JSON");
    }
    return canonical;
  }

  private record StoredFact(
      UUID eventId,
      long version,
      String eventType,
      String payload,
      String payloadSha256,
      boolean baseline) {}

  private record StreamIdentity(
      TaskBoardAggregateType aggregateType, UUID aggregateId, long version, UUID eventId) {}

  public record ReplayResult(long version, int factCount, String payloadSha256) {}

  public record ReplayParityResult(
      int aggregateCount, long versionSum, String canonicalChecksum) {}
}
