package dev.buhanzaz.rwms.logistics.order.domain.recovery;

import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderPaymentState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.proxy.HibernateProxy;

/**
 * Logistics-owned durable cancel/remove-unit intent, per-step receipt, recovery lease and terminal
 * quarantine state. Remote effects are never executed by this persistence model.
 */
@Entity
@Table(name = "rental_order_mutation_command")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RentalOrderMutationCommand {
  /** The same stable logistics service identity used by asset's authenticated private boundary. */
  public static final UUID AUTOMATIC_RELEASE_ACTOR_ID =
      UUID.nameUUIDFromBytes("service:logistics-service".getBytes(StandardCharsets.UTF_8));

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "order_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_rental_order_mutation_command_order"))
  private RentalOrder order;

  @Enumerated(EnumType.STRING)
  @Column(name = "operation", nullable = false, length = 32)
  private Operation operation;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 24)
  private State state;

  @Enumerated(EnumType.STRING)
  @Column(name = "step", nullable = false, length = 32)
  private Step step;

  @Column(name = "target_unit_id")
  private UUID targetUnitId;

  @Column(name = "expected_order_version", nullable = false)
  private long expectedOrderVersion;

  @Column(name = "actor_subject_id", nullable = false)
  private UUID actorSubjectId;

  @Column(name = "actor_role", nullable = false, length = 32)
  private String actorRole;

  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

  @JdbcTypeCode(Types.CHAR)
  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @Column(name = "warehouse_id")
  private UUID warehouseId;

  @Column(name = "release_units_idempotency_key", nullable = false)
  private UUID releaseUnitsIdempotencyKey;

  @Column(name = "release_equipment_idempotency_key", nullable = false)
  private UUID releaseEquipmentIdempotencyKey;

  @Column(name = "equipment_release_required", nullable = false)
  private boolean equipmentReleaseRequired;

  @Column(name = "intent_json", columnDefinition = "text")
  private String intentJson;

  @Column(name = "released_units_receipt_json", columnDefinition = "text")
  private String releasedUnitsReceiptJson;

  @Column(name = "equipment_receipt_json", columnDefinition = "text")
  private String equipmentReceiptJson;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "next_attempt_at")
  private OffsetDateTime nextAttemptAt;

  @Column(name = "lease_token")
  private UUID leaseToken;

  @Column(name = "lease_until")
  private OffsetDateTime leaseUntil;

  @Column(name = "last_error_code", length = 64)
  private String lastErrorCode;

  @Column(name = "quarantined_at")
  private OffsetDateTime quarantinedAt;

  @Column(name = "completed_at")
  private OffsetDateTime completedAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  /** Creates one immediately due command after its order and remote snapshot have been fenced. */
  public static RentalOrderMutationCommand start(
      RentalOrder order,
      Operation operation,
      UUID targetUnitId,
      long expectedOrderVersion,
      UUID actorSubjectId,
      String actorRole,
      UUID idempotencyKey,
      String requestSha256,
      UUID warehouseId,
      UUID releaseUnitsIdempotencyKey,
      UUID releaseEquipmentIdempotencyKey,
      String intentJson,
      OffsetDateTime timestamp) {
    RentalOrderMutationCommand command = new RentalOrderMutationCommand();
    command.order = Objects.requireNonNull(order, "order");
    command.operation = Objects.requireNonNull(operation, "operation");
    command.targetUnitId = targetUnitId;
    if (expectedOrderVersion < 0) {
      throw new IllegalArgumentException("expectedOrderVersion is invalid");
    }
    if ((operation.releasesAllUnits() && targetUnitId != null)
        || (operation == Operation.REMOVE_UNIT && (targetUnitId == null || warehouseId == null))) {
      throw new IllegalArgumentException("operation target is invalid");
    }
    command.expectedOrderVersion = expectedOrderVersion;
    command.actorSubjectId = Objects.requireNonNull(actorSubjectId, "actorSubjectId");
    command.actorRole = requiredText(actorRole, 32, "actorRole");
    boolean automaticExpiry = operation == Operation.EXPIRE_UNPAID_ORDER;
    if ((automaticExpiry
            && (!AUTOMATIC_RELEASE_ACTOR_ID.equals(actorSubjectId)
                || !"LOGISTICS_SERVICE".equals(actorRole)
                || warehouseId == null
                || order.getPaymentState() != RentalOrderPaymentState.EXPIRING
                || intentJson != null))
        || (!automaticExpiry && "LOGISTICS_SERVICE".equals(actorRole))) {
      throw new IllegalArgumentException("Automatic release provenance or state is invalid");
    }
    command.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    command.requestSha256 = requiredHash(requestSha256);
    command.warehouseId = warehouseId;
    command.releaseUnitsIdempotencyKey =
        Objects.requireNonNull(releaseUnitsIdempotencyKey, "releaseUnitsIdempotencyKey");
    command.releaseEquipmentIdempotencyKey =
        Objects.requireNonNull(releaseEquipmentIdempotencyKey, "releaseEquipmentIdempotencyKey");
    if (releaseUnitsIdempotencyKey.equals(releaseEquipmentIdempotencyKey)) {
      throw new IllegalArgumentException("step idempotency keys must differ");
    }
    command.equipmentReleaseRequired = warehouseId != null;
    command.intentJson =
        automaticExpiry ? null : requiredText(intentJson, Integer.MAX_VALUE, "intentJson");
    command.state = State.PENDING;
    command.step = automaticExpiry ? Step.READ_UNITS : Step.RELEASE_UNITS;
    command.nextAttemptAt = Objects.requireNonNull(timestamp, "timestamp");
    command.createdAt = timestamp;
    command.updatedAt = timestamp;
    return command;
  }

  /** Claims a due or expired command with one finite database-time lease. */
  public boolean claim(UUID token, OffsetDateTime timestamp, OffsetDateTime until) {
    OffsetDateTime now = Objects.requireNonNull(timestamp, "timestamp");
    OffsetDateTime requiredUntil = Objects.requireNonNull(until, "until");
    if (!requiredUntil.isAfter(now)) throw new IllegalArgumentException("lease is invalid");
    if (state != State.PENDING
        || nextAttemptAt == null
        || nextAttemptAt.isAfter(now)
        || leaseUntil != null && leaseUntil.isAfter(now)) {
      return false;
    }
    leaseToken = Objects.requireNonNull(token, "token");
    leaseUntil = requiredUntil;
    updatedAt = now;
    return true;
  }

  /** Freezes the first validated remote snapshot after the local payment-expiry fence commits. */
  public void recordIntent(UUID token, String value, OffsetDateTime timestamp) {
    requireLiveLease(token, timestamp);
    if (operation != Operation.EXPIRE_UNPAID_ORDER || step != Step.READ_UNITS) {
      throw new IllegalStateException("Automatic release snapshot is already settled");
    }
    intentJson = requiredText(value, Integer.MAX_VALUE, "intentJson");
    step = Step.RELEASE_UNITS;
    updatedAt = timestamp;
  }

  /** Records the proven release response and advances to the next required step. */
  public void recordReleasedUnits(
      UUID token, String receiptJson, OffsetDateTime timestamp) {
    requireLiveLease(token, timestamp);
    if (step != Step.RELEASE_UNITS) {
      throw new IllegalStateException("Unit release step is already settled");
    }
    releasedUnitsReceiptJson =
        requiredText(receiptJson, Integer.MAX_VALUE, "releasedUnitsReceiptJson");
    step = equipmentReleaseRequired ? Step.RELEASE_EQUIPMENT : Step.FINALIZE_LOCAL;
    updatedAt = timestamp;
  }

  /** Records the proven furniture response and advances to local finalization. */
  public void recordEquipmentReceipt(
      UUID token, String receiptJson, OffsetDateTime timestamp) {
    requireLiveLease(token, timestamp);
    if (!equipmentReleaseRequired || step != Step.RELEASE_EQUIPMENT) {
      throw new IllegalStateException("Equipment release step is not current");
    }
    equipmentReceiptJson = requiredText(receiptJson, Integer.MAX_VALUE, "equipmentReceiptJson");
    step = Step.FINALIZE_LOCAL;
    updatedAt = timestamp;
  }

  /** Completes the exact leased command after its local transition and receipt commit. */
  public void complete(UUID token, OffsetDateTime timestamp) {
    requireLiveLease(token, timestamp);
    if (step != Step.FINALIZE_LOCAL
        || releasedUnitsReceiptJson == null
        || equipmentReleaseRequired && equipmentReceiptJson == null) {
      throw new IllegalStateException("Mutation receipts are incomplete");
    }
    state = State.COMPLETED;
    step = Step.COMPLETED;
    nextAttemptAt = null;
    leaseToken = null;
    leaseUntil = null;
    lastErrorCode = null;
    completedAt = timestamp;
    updatedAt = timestamp;
  }

  /** Defers one failed leased attempt or moves it to terminal quarantine. */
  public void fail(
      UUID token,
      String errorCode,
      OffsetDateTime timestamp,
      OffsetDateTime retryAt,
      boolean quarantine) {
    requireLiveLease(token, timestamp);
    attemptCount = Math.addExact(attemptCount, 1);
    lastErrorCode = requiredText(errorCode, 64, "errorCode");
    leaseToken = null;
    leaseUntil = null;
    if (quarantine) {
      state = State.QUARANTINED;
      nextAttemptAt = null;
      quarantinedAt = timestamp;
    } else {
      OffsetDateTime next = Objects.requireNonNull(retryAt, "retryAt");
      if (!next.isAfter(timestamp)) throw new IllegalArgumentException("retryAt is invalid");
      nextAttemptAt = next;
    }
    updatedAt = timestamp;
  }

  /** Returns whether this pending command is fenced by the supplied unexpired lease. */
  public boolean hasLiveLease(UUID token, OffsetDateTime timestamp) {
    return state == State.PENDING
        && Objects.equals(leaseToken, token)
        && leaseUntil != null
        && leaseUntil.isAfter(Objects.requireNonNull(timestamp, "timestamp"));
  }

  private void requireLiveLease(UUID token, OffsetDateTime timestamp) {
    if (!hasLiveLease(token, timestamp)) {
      throw new IllegalStateException("Rental-order mutation lease is stale");
    }
  }

  private static String requiredText(String value, int maximum, String field) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > maximum) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }

  private static String requiredHash(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("requestSha256 is invalid");
    }
    return value;
  }

  /** Supported durable order mutations. */
  public enum Operation {
    CANCEL_ORDER,
    REMOVE_UNIT,
    EXPIRE_UNPAID_ORDER;

    /** Cancellation and unpaid expiry release the entire frozen order composition. */
    public boolean releasesAllUnits() {
      return this == CANCEL_ORDER || this == EXPIRE_UNPAID_ORDER;
    }
  }

  /** Durable command lifecycle including terminal operator-visible quarantine. */
  public enum State {
    PENDING,
    COMPLETED,
    QUARANTINED
  }

  /** Next replay-safe side effect or local transition required by the command. */
  public enum Step {
    READ_UNITS,
    RELEASE_UNITS,
    RELEASE_EQUIPMENT,
    FINALIZE_LOCAL,
    COMPLETED
  }

  @Override
  public final boolean equals(Object other) {
    if (this == other) return true;
    if (other == null) return false;
    Class<?> otherClass =
        other instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass()
            : other.getClass();
    Class<?> thisClass =
        this instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass()
            : getClass();
    return thisClass == otherClass
        && id != null
        && Objects.equals(id, ((RentalOrderMutationCommand) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
