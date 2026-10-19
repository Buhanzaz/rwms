package dev.buhanzaz.rwms.logistics.contractor.share.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.proxy.HibernateProxy;

/**
 * Logistics-owned capability root for one explicitly shared contractor route. The aggregate keeps
 * only local assignment identities and link lifecycle data; current execution state remains owned
 * by task-board and is resolved on every public request.
 */
@Entity
@Table(
    name = "contractor_route_share",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_contractor_route_share_subject_key",
            columnNames = {"created_by_subject_id", "idempotency_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContractorRouteShare {
  /** Maximum number of exact logistics tasks exposed by one contractor link. */
  public static final int MAXIMUM_TASKS = 50;

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "contractor_worker_id", nullable = false)
  private UUID contractorWorkerId;

  @Column(name = "created_by_subject_id", nullable = false)
  private UUID createdBySubjectId;

  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @Column(name = "token_revision", nullable = false)
  private long tokenRevision;

  @Column(name = "expires_at", nullable = false)
  private OffsetDateTime expiresAt;

  @Column(name = "revoked_at")
  private OffsetDateTime revokedAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @OneToMany(mappedBy = "share", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("position ASC")
  private List<ContractorRouteShareTask> tasks = new ArrayList<>();

  /** Creates one immutable task membership after local and task-board ownership was proven. */
  public static ContractorRouteShare create(
      UUID warehouseId,
      UUID contractorWorkerId,
      UUID createdBySubjectId,
      UUID idempotencyKey,
      String requestSha256,
      OffsetDateTime expiresAt,
      List<TaskBinding> taskBindings,
      OffsetDateTime createdAt) {
    Objects.requireNonNull(createdAt, "createdAt");
    if (expiresAt == null || !expiresAt.isAfter(createdAt)) {
      throw new IllegalArgumentException("Contractor route share expiry is invalid");
    }
    List<TaskBinding> bindings = taskBindings == null ? List.of() : List.copyOf(taskBindings);
    if (bindings.isEmpty() || bindings.size() > MAXIMUM_TASKS) {
      throw new IllegalArgumentException("Contractor route share task count is invalid");
    }
    Set<UUID> externalTaskIds = new HashSet<>();
    if (bindings.stream()
        .anyMatch(binding -> binding == null || !externalTaskIds.add(binding.externalTaskId()))) {
      throw new IllegalArgumentException("Contractor route share tasks must be unique");
    }
    ContractorRouteShare share = new ContractorRouteShare();
    share.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
    share.contractorWorkerId = Objects.requireNonNull(contractorWorkerId, "contractorWorkerId");
    share.createdBySubjectId = Objects.requireNonNull(createdBySubjectId, "createdBySubjectId");
    share.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    share.requestSha256 = requireHash(requestSha256);
    share.tokenRevision = 1;
    share.expiresAt = expiresAt;
    share.createdAt = createdAt;
    share.updatedAt = createdAt;
    for (int position = 0; position < bindings.size(); position++) {
      share.tasks.add(ContractorRouteShareTask.create(share, position, bindings.get(position)));
    }
    return share;
  }

  /**
   * Revokes the public capability and rotates its token revision under an external version fence.
   */
  public void revoke(OffsetDateTime timestamp) {
    Objects.requireNonNull(timestamp, "timestamp");
    if (revokedAt != null) return;
    revokedAt = timestamp;
    updatedAt = timestamp;
    tokenRevision = Math.addExact(tokenRevision, 1);
  }

  /**
   * Returns whether this exact signed revision remains usable at the supplied authoritative time.
   */
  public boolean isAvailable(long expectedTokenRevision, OffsetDateTime timestamp) {
    return tokenRevision == expectedTokenRevision
        && revokedAt == null
        && timestamp != null
        && timestamp.isBefore(expiresAt);
  }

  /** Returns whether a subject-scoped idempotency retry has the original canonical request. */
  public boolean matchesRequest(String candidateSha256) {
    return requestSha256.equals(candidateSha256);
  }

  /** Immutable local binding captured for each exact external logistics task. */
  public record TaskBinding(
      UUID externalTaskId, UUID driverTaskId, UUID documentId, UUID rentalOrderId) {
    public TaskBinding {
      Objects.requireNonNull(externalTaskId, "externalTaskId");
      Objects.requireNonNull(driverTaskId, "driverTaskId");
      Objects.requireNonNull(documentId, "documentId");
    }
  }

  private static String requireHash(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("requestSha256 is invalid");
    }
    return value;
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
        && Objects.equals(id, ((ContractorRouteShare) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
