package dev.buhanzaz.rwms.maintenance.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Durable node-UUID to asset equipment mapping and its bounded reviewed reconciliation. */
@Repository
public class FurnitureEquipmentLinkStore {
  public static final int MAX_ATTEMPTS = 8;

  private final JdbcTemplate jdbc;

  public FurnitureEquipmentLinkStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Commits every missing local intent as one unit before any caller may contact asset-service. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public List<LinkSnapshot> prepareAll(
      UUID warehouseId,
      UUID catalogVersionId,
      long catalogExpectedVersion,
      List<LinkRequirement> requirements) {
    if (warehouseId == null
        || catalogVersionId == null
        || catalogExpectedVersion < 0
        || requirements == null
        || requirements.stream().anyMatch(java.util.Objects::isNull)) {
      throw new IllegalArgumentException("Furniture equipment link intent is invalid");
    }
    Map<UUID, String> requested = new LinkedHashMap<>();
    for (LinkRequirement requirement : requirements) {
      String name = normalizeName(requirement.requestedName());
      String previous = requested.putIfAbsent(requirement.nodeId(), name);
      if (requirement.nodeId() == null || (previous != null && !previous.equals(name))) {
        throw new IllegalArgumentException("Furniture equipment node requirements conflict");
      }
    }
    List<UUID> orderedIds = requested.keySet().stream().sorted().toList();
    for (UUID nodeId : orderedIds) {
      jdbc.query(
          "select pg_advisory_xact_lock(hashtextextended(?, 0))",
          resultSet -> {},
          "maintenance:furniture-equipment-link:" + nodeId);
    }
    OffsetDateTime now = now();
    List<LinkSnapshot> result = new ArrayList<>(orderedIds.size());
    for (UUID nodeId : orderedIds) {
      LinkSnapshot existing = findForUpdate(nodeId).orElse(null);
      String requestedName = requested.get(nodeId);
      if (existing == null) {
        jdbc.update(
            """
            insert into furniture_equipment_link_intent(
              node_id,warehouse_id,source_catalog_version_id,source_catalog_expected_version,
              requested_name,state,attempt_count,next_attempt_at,review_version,created_at,updated_at)
            values (?,?,?,?,?,'PENDING',0,?,0,?,?)
            """,
            nodeId,
            warehouseId,
            catalogVersionId,
            catalogExpectedVersion,
            requestedName,
            now,
            now,
            now);
        existing = findForUpdate(nodeId).orElseThrow();
      } else {
        requireStableRequest(existing, requestedName);
      }
      result.add(existing);
    }
    return List.copyOf(result);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<WorkItem> claimExact(UUID nodeId, Duration lease) {
    requireClaimRequest(nodeId, lease);
    OffsetDateTime claimedAt = now();
    LinkSnapshot candidate = findForUpdate(nodeId)
        .orElseThrow(() -> new MaintenanceNotFoundException(
            "Furniture equipment link intent not found"));
    if (candidate.attemptCount() >= MAX_ATTEMPTS
        || "CONFIRMED".equals(candidate.state())
        || "REVIEW_REQUIRED".equals(candidate.state())
        || "ABANDONED".equals(candidate.state())
        || ("IN_FLIGHT".equals(candidate.state())
            && candidate.claimUntil() != null
            && candidate.claimUntil().isAfter(claimedAt))) {
      return Optional.empty();
    }
    return claim(candidate, claimedAt, lease);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<WorkItem> claimNextDue(Duration lease) {
    requireClaimRequest(UUID.randomUUID(), lease);
    OffsetDateTime claimedAt = now();
    LinkSnapshot candidate = jdbc.query(
            """
            select *
              from furniture_equipment_link_intent
             where attempt_count < ?
               and ((state in ('PENDING','RETRY_PENDING') and next_attempt_at <= ?)
                 or (state='IN_FLIGHT' and claim_until <= ?))
             order by
               case when state='IN_FLIGHT' then claim_until else next_attempt_at end,
               warehouse_id,
               node_id
             for update skip locked
             limit 1
            """,
            this::map,
            MAX_ATTEMPTS,
            claimedAt,
            claimedAt)
        .stream()
        .findFirst()
        .orElse(null);
    return candidate == null ? Optional.empty() : claim(candidate, claimedAt, lease);
  }

  private Optional<WorkItem> claim(
      LinkSnapshot candidate, OffsetDateTime claimedAt, Duration lease) {
    UUID claimToken = UUID.randomUUID();
    OffsetDateTime claimUntil = claimedAt.plus(lease);
    int changed = jdbc.update(
        """
        update furniture_equipment_link_intent
           set state='IN_FLIGHT',claim_token=?,claim_until=?,updated_at=?
         where node_id=? and state=? and attempt_count=?
        """,
        claimToken,
        claimUntil,
        claimedAt,
        candidate.nodeId(),
        candidate.state(),
        candidate.attemptCount());
    if (changed != 1) throw claimChanged();
    return Optional.of(new WorkItem(
        candidate.nodeId(),
        candidate.warehouseId(),
        candidate.requestedName(),
        candidate.attemptCount(),
        claimToken,
        claimUntil));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void confirmed(WorkItem work, UUID equipmentId, String equipmentName) {
    requireClaim(work);
    String normalizedEquipmentName = normalizeName(equipmentName);
    if (equipmentId == null || !work.requestedName().equals(normalizedEquipmentName)) {
      throw new IllegalArgumentException("Confirmed furniture equipment mapping is invalid");
    }
    OffsetDateTime confirmedAt = now();
    int changed = jdbc.update(
        """
        update furniture_equipment_link_intent
           set state='CONFIRMED',equipment_id=?,equipment_name=?,
               observed_equipment_id=?,observed_equipment_name=?,
               attempt_count=attempt_count+1,claim_token=null,claim_until=null,
               last_error_code=null,last_error_detail=null,confirmed_at=?,updated_at=?
         where node_id=? and state='IN_FLIGHT' and claim_token=? and attempt_count=?
        """,
        equipmentId,
        normalizedEquipmentName,
        equipmentId,
        normalizedEquipmentName,
        confirmedAt,
        confirmedAt,
        work.nodeId(),
        work.claimToken(),
        work.attemptCount());
    if (changed != 1) throw claimChanged();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void mappingConflict(
      WorkItem work, UUID observedEquipmentId, String observedEquipmentName, String detail) {
    requireClaim(work);
    String observedName = observedEquipmentName == null ? null : normalizeName(observedEquipmentName);
    String normalizedDetail = normalizeDetail(detail);
    int changed = jdbc.update(
        """
        update furniture_equipment_link_intent
           set state='REVIEW_REQUIRED',observed_equipment_id=?,observed_equipment_name=?,
               attempt_count=attempt_count+1,claim_token=null,claim_until=null,
               last_error_code='ASSET_MAPPING_CONFLICT',last_error_detail=?,updated_at=?
         where node_id=? and state='IN_FLIGHT' and claim_token=? and attempt_count=?
        """,
        observedEquipmentId,
        observedName,
        normalizedDetail,
        now(),
        work.nodeId(),
        work.claimToken(),
        work.attemptCount());
    if (changed != 1) throw claimChanged();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean failed(WorkItem work, RuntimeException failure) {
    requireClaim(work);
    if (failure == null) throw new IllegalArgumentException("Furniture link failure is required");
    int nextAttempt = Math.addExact(work.attemptCount(), 1);
    boolean reviewRequired = nextAttempt >= MAX_ATTEMPTS;
    OffsetDateTime failedAt = now();
    OffsetDateTime next = failedAt.plusSeconds(1L << Math.min(work.attemptCount(), 6));
    int changed = jdbc.update(
        """
        update furniture_equipment_link_intent
           set state=?,attempt_count=?,next_attempt_at=?,claim_token=null,claim_until=null,
               last_error_code=?,last_error_detail=?,updated_at=?
         where node_id=? and state='IN_FLIGHT' and claim_token=? and attempt_count=?
        """,
        reviewRequired ? "REVIEW_REQUIRED" : "RETRY_PENDING",
        nextAttempt,
        next,
        failureCode(failure),
        normalizeDetail(failure.getMessage()),
        failedAt,
        work.nodeId(),
        work.claimToken(),
        work.attemptCount());
    if (changed != 1) throw claimChanged();
    return reviewRequired;
  }

  @Transactional(readOnly = true)
  public LinkSnapshot require(UUID nodeId) {
    if (nodeId == null) throw new IllegalArgumentException("Furniture node identity is required");
    return find(nodeId).orElseThrow(
        () -> new MaintenanceNotFoundException("Furniture equipment link intent not found"));
  }

  @Transactional(readOnly = true)
  public LinkPage list(UUID warehouseId, String state, int page, int size) {
    if (warehouseId == null || page < 0 || size < 1 || size > 200) {
      throw new IllegalArgumentException("Furniture equipment link page is invalid");
    }
    String normalizedState = state == null ? null : state.trim().toUpperCase(java.util.Locale.ROOT);
    if (normalizedState != null && !STATES.contains(normalizedState)) {
      throw new IllegalArgumentException("Furniture equipment link state is invalid");
    }
    long total = jdbc.queryForObject(
        """
        select count(*) from furniture_equipment_link_intent
         where warehouse_id=? and (cast(? as varchar) is null or state=?)
        """,
        Long.class,
        warehouseId,
        normalizedState,
        normalizedState);
    List<LinkSnapshot> items = jdbc.query(
        """
        select * from furniture_equipment_link_intent
         where warehouse_id=? and (cast(? as varchar) is null or state=?)
         order by updated_at desc,node_id
         limit ? offset ?
        """,
        this::map,
        warehouseId,
        normalizedState,
        normalizedState,
        size,
        Math.multiplyExact(page, size));
    return new LinkPage(items, page, size, total);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ReviewResult review(
      UUID warehouseId,
      UUID nodeId,
      long expectedReviewVersion,
      ReviewAction action,
      UUID reviewSubjectId,
      String reason) {
    if (warehouseId == null
        || nodeId == null
        || expectedReviewVersion < 0
        || action == null
        || reviewSubjectId == null
        || reason == null
        || reason.isBlank()
        || reason.trim().length() > 2000) {
      throw new IllegalArgumentException("Furniture equipment link review is invalid");
    }
    String normalizedReason = reason.trim();
    LinkSnapshot row = findForUpdate(nodeId)
        .orElseThrow(() -> new MaintenanceNotFoundException(
            "Furniture equipment link intent not found"));
    if (!warehouseId.equals(row.warehouseId())) {
      throw new MaintenanceNotFoundException("Furniture equipment link intent not found");
    }
    long nextReviewVersion = Math.addExact(expectedReviewVersion, 1);
    if (row.reviewVersion() == nextReviewVersion) {
      ReviewAudit replay = findReviewAudit(nodeId, nextReviewVersion).orElseThrow();
      if (action.name().equals(replay.action())
          && reviewSubjectId.equals(replay.reviewSubjectId())
          && normalizedReason.equals(replay.reason())) {
        return new ReviewResult(row, true);
      }
      throw new MaintenanceConflictException(
          "MAINTENANCE_IDEMPOTENCY_CONFLICT",
          "Furniture equipment link review version is bound to another command");
    }
    if (row.reviewVersion() != expectedReviewVersion) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_VERSION_CONFLICT",
          "Furniture equipment link review version is stale");
    }
    if (action == ReviewAction.RETRY && !"REVIEW_REQUIRED".equals(row.state())) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Only a review-required furniture equipment link can be retried");
    }
    if (action == ReviewAction.ABANDON
        && !List.of("PENDING", "RETRY_PENDING", "REVIEW_REQUIRED").contains(row.state())) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "Only pending or review-required furniture equipment links can be abandoned");
    }
    OffsetDateTime reviewedAt = now();
    jdbc.update(
        """
        insert into furniture_equipment_link_review_audit(
          id,node_id,review_version,action,previous_state,
          reviewed_by_subject_id,reason,reviewed_at)
        values (?,?,?,?,?,?,?,?)
        """,
        UUID.randomUUID(),
        nodeId,
        nextReviewVersion,
        action.name(),
        row.state(),
        reviewSubjectId,
        normalizedReason,
        reviewedAt);
    int changed;
    if (action == ReviewAction.RETRY) {
      changed = jdbc.update(
          """
          update furniture_equipment_link_intent
             set state='PENDING',attempt_count=0,next_attempt_at=?,
                 last_error_code=null,last_error_detail=null,review_version=?,
                 reviewed_by_subject_id=?,review_action='RETRY',review_reason=?,reviewed_at=?,
                 updated_at=?
           where node_id=? and state='REVIEW_REQUIRED' and review_version=?
          """,
          reviewedAt,
          nextReviewVersion,
          reviewSubjectId,
          normalizedReason,
          reviewedAt,
          reviewedAt,
          nodeId,
          expectedReviewVersion);
    } else {
      changed = jdbc.update(
          """
          update furniture_equipment_link_intent
             set state='ABANDONED',next_attempt_at=?,claim_token=null,claim_until=null,
                 review_version=?,reviewed_by_subject_id=?,review_action='ABANDON',
                 review_reason=?,reviewed_at=?,abandoned_at=?,updated_at=?
           where node_id=? and state in ('PENDING','RETRY_PENDING','REVIEW_REQUIRED')
             and review_version=?
          """,
          reviewedAt,
          nextReviewVersion,
          reviewSubjectId,
          normalizedReason,
          reviewedAt,
          reviewedAt,
          reviewedAt,
          nodeId,
          expectedReviewVersion);
    }
    if (changed != 1) throw claimChanged();
    return new ReviewResult(findForUpdate(nodeId).orElseThrow(), false);
  }

  private Optional<LinkSnapshot> find(UUID nodeId) {
    return jdbc.query(
            "select * from furniture_equipment_link_intent where node_id=?",
            this::map,
            nodeId)
        .stream()
        .findFirst();
  }

  private Optional<LinkSnapshot> findForUpdate(UUID nodeId) {
    return jdbc.query(
            "select * from furniture_equipment_link_intent where node_id=? for update",
            this::map,
            nodeId)
        .stream()
        .findFirst();
  }

  private Optional<ReviewAudit> findReviewAudit(UUID nodeId, long reviewVersion) {
    return jdbc.query(
            """
            select action,reviewed_by_subject_id,reason
              from furniture_equipment_link_review_audit
             where node_id=? and review_version=?
            """,
            (rs, ignored) -> new ReviewAudit(
                rs.getString("action"),
                rs.getObject("reviewed_by_subject_id", UUID.class),
                rs.getString("reason")),
            nodeId,
            reviewVersion)
        .stream()
        .findFirst();
  }

  private LinkSnapshot map(java.sql.ResultSet rs, int ignored) throws java.sql.SQLException {
    return new LinkSnapshot(
        rs.getObject("node_id", UUID.class),
        rs.getObject("warehouse_id", UUID.class),
        rs.getObject("source_catalog_version_id", UUID.class),
        rs.getLong("source_catalog_expected_version"),
        rs.getString("requested_name"),
        rs.getString("state"),
        rs.getObject("equipment_id", UUID.class),
        rs.getString("equipment_name"),
        rs.getObject("observed_equipment_id", UUID.class),
        rs.getString("observed_equipment_name"),
        rs.getInt("attempt_count"),
        rs.getObject("next_attempt_at", OffsetDateTime.class),
        rs.getObject("claim_token", UUID.class),
        rs.getObject("claim_until", OffsetDateTime.class),
        rs.getString("last_error_code"),
        rs.getString("last_error_detail"),
        rs.getLong("review_version"),
        rs.getObject("reviewed_by_subject_id", UUID.class),
        rs.getString("review_action"),
        rs.getString("review_reason"),
        rs.getObject("reviewed_at", OffsetDateTime.class),
        rs.getObject("confirmed_at", OffsetDateTime.class),
        rs.getObject("abandoned_at", OffsetDateTime.class),
        rs.getObject("created_at", OffsetDateTime.class),
        rs.getObject("updated_at", OffsetDateTime.class));
  }

  private static void requireStableRequest(LinkSnapshot existing, String requestedName) {
    if (!requestedName.equals(existing.requestedName())) {
      throw new MaintenanceConflictException(
          "FURNITURE_EQUIPMENT_LINK_CONFLICT",
          "Furniture node UUID is already bound to another immutable equipment name");
    }
    if ("ABANDONED".equals(existing.state())) {
      throw new MaintenanceConflictException(
          "FURNITURE_EQUIPMENT_LINK_ABANDONED",
          "Furniture equipment link was abandoned; use a new node UUID or review the catalog");
    }
  }

  private static void requireClaimRequest(UUID nodeId, Duration lease) {
    if (nodeId == null
        || lease == null
        || lease.isZero()
        || lease.isNegative()
        || lease.compareTo(Duration.ofHours(1)) > 0) {
      throw new IllegalArgumentException("Furniture equipment link claim is invalid");
    }
  }

  private static void requireClaim(WorkItem work) {
    if (work == null
        || work.nodeId() == null
        || work.warehouseId() == null
        || work.requestedName() == null
        || work.requestedName().isBlank()
        || work.attemptCount() < 0
        || work.claimToken() == null
        || work.claimUntil() == null) {
      throw new IllegalArgumentException("Furniture equipment link claim is invalid");
    }
  }

  private static String normalizeName(String value) {
    if (value == null || value.isBlank() || value.trim().length() > 255) {
      throw new IllegalArgumentException("Furniture equipment name is invalid");
    }
    return value.trim();
  }

  private static String normalizeDetail(String value) {
    String detail = value == null || value.isBlank() ? "Unspecified dependency failure" : value.trim();
    return detail.length() <= 2000 ? detail : detail.substring(0, 2000);
  }

  private static String failureCode(RuntimeException failure) {
    if (failure instanceof MaintenanceDependencyException dependency) {
      return "HTTP_" + dependency.status().value();
    }
    String name = failure.getClass().getSimpleName();
    return name.length() <= 64 ? name : name.substring(0, 64);
  }

  private static MaintenanceConflictException claimChanged() {
    return new MaintenanceConflictException(
        "MAINTENANCE_VERSION_CONFLICT", "Furniture equipment link claim changed");
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  private static final java.util.Set<String> STATES = java.util.Set.of(
      "PENDING", "IN_FLIGHT", "RETRY_PENDING", "CONFIRMED", "REVIEW_REQUIRED", "ABANDONED");

  public enum ReviewAction { RETRY, ABANDON }

  public record LinkRequirement(UUID nodeId, String requestedName) {}

  public record WorkItem(
      UUID nodeId,
      UUID warehouseId,
      String requestedName,
      int attemptCount,
      UUID claimToken,
      OffsetDateTime claimUntil) {}

  public record LinkSnapshot(
      UUID nodeId,
      UUID warehouseId,
      UUID sourceCatalogVersionId,
      long sourceCatalogExpectedVersion,
      String requestedName,
      String state,
      UUID equipmentId,
      String equipmentName,
      UUID observedEquipmentId,
      String observedEquipmentName,
      int attemptCount,
      OffsetDateTime nextAttemptAt,
      UUID claimToken,
      OffsetDateTime claimUntil,
      String lastErrorCode,
      String lastErrorDetail,
      long reviewVersion,
      UUID reviewedBySubjectId,
      String reviewAction,
      String reviewReason,
      OffsetDateTime reviewedAt,
      OffsetDateTime confirmedAt,
      OffsetDateTime abandonedAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record LinkPage(List<LinkSnapshot> items, int page, int size, long total) {
    public LinkPage {
      items = List.copyOf(items);
    }
  }

  public record ReviewResult(LinkSnapshot snapshot, boolean replayed) {}

  private record ReviewAudit(String action, UUID reviewSubjectId, String reason) {}
}
