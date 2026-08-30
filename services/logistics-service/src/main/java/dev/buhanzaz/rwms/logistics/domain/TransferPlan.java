package dev.buhanzaz.rwms.logistics.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.proxy.HibernateProxy;

/**
 * Logistics-owned planning projection attached to the existing transfer aggregate. It keeps cargo
 * requirements and resource intents separate from the physical document lines used by the
 * departure/arrival saga.
 */
@Entity
@Table(
    name = "transfer_plan",
    uniqueConstraints =
        @UniqueConstraint(name = "uk_transfer_plan_document", columnNames = "document_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransferPlan {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @NotNull
  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "document_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_transfer_plan_document"))
  private LogisticsDocument document;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 16)
  private TransferPlanState state;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "reservation_readiness", nullable = false, length = 24)
  private TransferReservationReadiness reservationReadiness;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "workflow_state", nullable = false, length = 32)
  private TransferPlanWorkflowState workflowState;

  @Column(name = "workflow_failure_code", length = 96)
  private String workflowFailureCode;

  @Column(name = "planned_departure_at")
  private OffsetDateTime plannedDepartureAt;

  @Column(name = "planned_arrival_at")
  private OffsetDateTime plannedArrivalAt;

  @Size(max = 2_000)
  @Column(name = "logistics_comment", length = 2_000)
  private String logisticsComment;

  @Column(name = "trip_driver_id")
  private UUID tripDriverId;

  @Column(name = "trip_vehicle_id")
  private UUID tripVehicleId;

  @Column(name = "repositioned_driver_id")
  private UUID repositionedDriverId;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "driver_reposition_mode", nullable = false, length = 16)
  private TransferResourceRepositionMode driverRepositionMode;

  @Column(name = "driver_reposition_until")
  private OffsetDateTime driverRepositionUntil;

  @Column(name = "repositioned_vehicle_id")
  private UUID repositionedVehicleId;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "vehicle_reposition_mode", nullable = false, length = 16)
  private TransferResourceRepositionMode vehicleRepositionMode;

  @Column(name = "vehicle_reposition_until")
  private OffsetDateTime vehicleRepositionUntil;

  @Column(name = "trip_driver_assignment_id")
  private UUID tripDriverAssignmentId;

  @Column(name = "trip_driver_assignment_version")
  private Long tripDriverAssignmentVersion;

  @Column(name = "trip_driver_assignment_status", length = 16)
  private String tripDriverAssignmentStatus;

  @Column(name = "repositioned_driver_assignment_id")
  private UUID repositionedDriverAssignmentId;

  @Column(name = "repositioned_driver_assignment_version")
  private Long repositionedDriverAssignmentVersion;

  @Column(name = "repositioned_driver_assignment_status", length = 16)
  private String repositionedDriverAssignmentStatus;

  @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("position ASC")
  private List<TransferCargoGroup> cabinGroups = new ArrayList<>();

  @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("position ASC")
  private List<TransferLooseFurniture> looseFurniture = new ArrayList<>();

  @NotNull
  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @NotNull
  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  /** Creates an editable plan without producing asset, driver, or stock effects. */
  public static TransferPlan draft(LogisticsDocument document, TransferPlanDraft draft) {
    if (document == null || document.getDocumentType() != LogisticsDocumentType.TRANSFER) {
      throw new IllegalArgumentException("Transfer document is required");
    }
    TransferPlan plan = new TransferPlan();
    plan.document = document;
    plan.state = TransferPlanState.DRAFT;
    plan.reservationReadiness = TransferReservationReadiness.NOT_RESERVED;
    plan.workflowState = TransferPlanWorkflowState.NOT_STARTED;
    plan.replace(draft);
    return plan;
  }

  /** Replaces the complete editable planning projection under the document version fence. */
  public void replace(TransferPlanDraft draft) {
    if (state != TransferPlanState.DRAFT) {
      throw new IllegalStateException("Only a draft transfer plan can be changed");
    }
    TransferPlanDraft validated = validateDraft(draft, false);
    plannedDepartureAt = validated.plannedDepartureAt();
    plannedArrivalAt = validated.plannedArrivalAt();
    logisticsComment = normalizeComment(validated.logisticsComment());
    tripDriverId = validated.tripDriverId();
    tripVehicleId = validated.tripVehicleId();
    applyDriverIntent(validated.driverReposition());
    applyVehicleIntent(validated.vehicleReposition());
    cabinGroups.clear();
    for (int index = 0; index < validated.cabinGroups().size(); index++) {
      cabinGroups.add(TransferCargoGroup.create(this, index + 1, validated.cabinGroups().get(index)));
    }
    looseFurniture.clear();
    for (int index = 0; index < validated.looseFurniture().size(); index++) {
      looseFurniture.add(
          TransferLooseFurniture.create(this, index + 1, validated.looseFurniture().get(index)));
    }
    touch();
  }

  /**
   * Deletes prior child rows before a full draft replacement so database uniqueness constraints
   * cannot observe old and new ordered positions in the same flush.
   */
  public void clearCargoForReplacement() {
    if (state != TransferPlanState.DRAFT) {
      throw new IllegalStateException("Only a draft transfer plan can be changed");
    }
    cabinGroups.clear();
    looseFurniture.clear();
    touch();
  }

  /**
   * Freezes the editable plan after structural validation. Reservation readiness deliberately
   * remains {@code NOT_RESERVED} until asset-service owns durable cabin and loose-stock holds.
   */
  public void confirm() {
    if (state != TransferPlanState.DRAFT) {
      throw new IllegalStateException("Transfer plan is already confirmed");
    }
    validateDraft(toDraft(), true);
    state = TransferPlanState.CONFIRMED;
    reservationReadiness = TransferReservationReadiness.RESERVING;
    workflowState = TransferPlanWorkflowState.RESERVING;
    workflowFailureCode = null;
    touch();
  }

  /** Future asset-owned reservation integration may open departure only through this transition. */
  public void markReserved() {
    if (state != TransferPlanState.CONFIRMED) {
      throw new IllegalStateException("Only a confirmed transfer plan can become reserved");
    }
    reservationReadiness = TransferReservationReadiness.RESERVED;
    workflowState = TransferPlanWorkflowState.READY;
    workflowFailureCode = null;
    touch();
  }

  /** Marks every locally tracked cargo/resource hold as physically travelling. */
  public void markInTransit() {
    if (workflowState == TransferPlanWorkflowState.IN_TRANSIT) return;
    if (!isDepartureReady()) {
      throw new IllegalStateException("Only a ready transfer plan can enter transit");
    }
    workflowState = TransferPlanWorkflowState.IN_TRANSIT;
    for (TransferLooseFurniture item : looseFurniture) item.markInTransit();
    touch();
  }

  /** Starts completion after every concrete cabin has confirmed destination unloading. */
  public void beginCompletion() {
    if (workflowState == TransferPlanWorkflowState.COMPLETED) return;
    if (workflowState != TransferPlanWorkflowState.IN_TRANSIT
        && workflowState != TransferPlanWorkflowState.COMPLETING) {
      throw new IllegalStateException("Transfer plan is not ready for completion");
    }
    workflowState = TransferPlanWorkflowState.COMPLETING;
    touch();
  }

  /** Records that every cargo and operational-resource completion effect is durable. */
  public void completeWorkflow() {
    if (workflowState == TransferPlanWorkflowState.COMPLETED) return;
    if (workflowState != TransferPlanWorkflowState.COMPLETING) {
      throw new IllegalStateException("Transfer plan completion effects are incomplete");
    }
    workflowState = TransferPlanWorkflowState.COMPLETED;
    touch();
  }

  /** Requests release of every hold while the physical trip is still pre-start. */
  public void beginRelease() {
    if (workflowState == TransferPlanWorkflowState.RELEASED) return;
    if (workflowState == TransferPlanWorkflowState.IN_TRANSIT
        || workflowState == TransferPlanWorkflowState.COMPLETING
        || workflowState == TransferPlanWorkflowState.COMPLETED) {
      throw new IllegalStateException("In-transit cargo requires an explicit destination decision");
    }
    reservationReadiness = TransferReservationReadiness.RELEASING;
    workflowState = TransferPlanWorkflowState.RELEASING;
    workflowFailureCode = null;
    touch();
  }

  /** Finishes a pre-start compensation only after every remote hold is released. */
  public void finishRelease() {
    if (workflowState == TransferPlanWorkflowState.RELEASED) return;
    if (workflowState != TransferPlanWorkflowState.RELEASING) {
      throw new IllegalStateException("Transfer plan is not releasing");
    }
    reservationReadiness = TransferReservationReadiness.RELEASED;
    workflowState = TransferPlanWorkflowState.RELEASED;
    touch();
  }

  /** Records a known rejection while retaining successfully acquired holds for compensation. */
  public void conflict(String code) {
    workflowFailureCode = requireFailureCode(code);
    reservationReadiness = TransferReservationReadiness.FAILED;
    workflowState = TransferPlanWorkflowState.CONFLICT;
    touch();
  }

  /** Records an ambiguous remote outcome which must not be guessed or silently retried forever. */
  public void requireReconciliation(String code) {
    workflowFailureCode = requireFailureCode(code);
    reservationReadiness = TransferReservationReadiness.FAILED;
    workflowState = TransferPlanWorkflowState.RECONCILIATION_REQUIRED;
    touch();
  }

  /** Freezes one exact asset balance before the first loose-furniture reservation call. */
  public void freezeLooseFurnitureSource(
      int position, UUID balanceId, long expectedBalanceVersion) {
    looseFurniture(position).freezeSource(balanceId, expectedBalanceVersion);
    touch();
  }

  /** Stores one asset-owned loose-furniture reservation receipt. */
  public void attachLooseFurnitureReservation(
      int position, UUID reservationId, long reservationVersion, UUID sourceBalanceId) {
    looseFurniture(position)
        .attachReservation(reservationId, reservationVersion, sourceBalanceId);
    touch();
  }

  /** Stores a replay-safe release result for one independent furniture line. */
  public void releaseLooseFurniture(int position, long reservationVersion) {
    looseFurniture(position).release(reservationVersion);
    touch();
  }

  /** Stores a replay-safe destination stock movement result for one furniture line. */
  public void executeLooseFurniture(int position, long reservationVersion) {
    looseFurniture(position).execute(reservationVersion);
    touch();
  }

  /** Persists the trip driver's separate route commitment. */
  public void attachTripDriverAssignment(UUID assignmentId, long version, String status) {
    tripDriverAssignmentId = stableAssignmentId(tripDriverAssignmentId, assignmentId);
    tripDriverAssignmentVersion = monotonicVersion(tripDriverAssignmentVersion, version);
    tripDriverAssignmentStatus = assignmentStatus(status);
    touch();
  }

  /** Persists the destination-placement assignment, separate from the driver executing the trip. */
  public void attachRepositionedDriverAssignment(
      UUID assignmentId, long version, String status) {
    repositionedDriverAssignmentId =
        stableAssignmentId(repositionedDriverAssignmentId, assignmentId);
    repositionedDriverAssignmentVersion =
        monotonicVersion(repositionedDriverAssignmentVersion, version);
    repositionedDriverAssignmentStatus = assignmentStatus(status);
    touch();
  }

  /** Returns whether the old departure saga may start without inventing a reservation. */
  public boolean isDepartureReady() {
    return state == TransferPlanState.CONFIRMED
        && reservationReadiness == TransferReservationReadiness.RESERVED;
  }

  /** Materializes an immutable projection with calculated per-group furniture totals. */
  public TransferPlanSnapshot snapshot() {
    List<TransferPlanSnapshot.CargoGroup> groups =
        cabinGroups.stream().map(TransferCargoGroup::snapshot).toList();
    List<TransferPlanSnapshot.LooseFurniture> loose =
        looseFurniture.stream().map(TransferLooseFurniture::snapshot).toList();
    return new TransferPlanSnapshot(
        id,
        version,
        state,
        reservationReadiness,
        workflowState,
        workflowFailureCode,
        plannedDepartureAt,
        plannedArrivalAt,
        logisticsComment,
        tripDriverId,
        tripVehicleId,
        new TransferPlanSnapshot.ResourceIntent(
            repositionedDriverId, driverRepositionMode, driverRepositionUntil),
        new TransferPlanSnapshot.ResourceIntent(
            repositionedVehicleId, vehicleRepositionMode, vehicleRepositionUntil),
        new TransferPlanSnapshot.Assignment(
            tripDriverAssignmentId,
            tripDriverAssignmentVersion,
            tripDriverAssignmentStatus),
        new TransferPlanSnapshot.Assignment(
            repositionedDriverAssignmentId,
            repositionedDriverAssignmentVersion,
            repositionedDriverAssignmentStatus),
        groups,
        loose,
        totalCabinCount(),
        auditLineCount());
  }

  /** Number of concrete cabins required by all groups. */
  public int totalCabinCount() {
    int total = 0;
    for (TransferCargoGroup group : cabinGroups) total = Math.addExact(total, group.getQuantity());
    return total;
  }

  /**
   * Safe event-envelope line count while a plan has no physical document lines. Loose furniture
   * contributes one requirement line per distinct catalog item.
   */
  public int auditLineCount() {
    return Math.addExact(totalCabinCount(), looseFurniture.size());
  }

  private TransferPlanDraft toDraft() {
    return new TransferPlanDraft(
        plannedDepartureAt,
        plannedArrivalAt,
        logisticsComment,
        tripDriverId,
        tripVehicleId,
        new TransferPlanDraft.ResourceIntent(
            repositionedDriverId, driverRepositionMode, driverRepositionUntil),
        new TransferPlanDraft.ResourceIntent(
            repositionedVehicleId, vehicleRepositionMode, vehicleRepositionUntil),
        cabinGroups.stream().map(TransferCargoGroup::toDraft).toList(),
        looseFurniture.stream().map(TransferLooseFurniture::toDraft).toList());
  }

  private void applyDriverIntent(TransferPlanDraft.ResourceIntent intent) {
    repositionedDriverId = intent.resourceId();
    driverRepositionMode = intent.mode();
    driverRepositionUntil = intent.until();
  }

  private void applyVehicleIntent(TransferPlanDraft.ResourceIntent intent) {
    repositionedVehicleId = intent.resourceId();
    vehicleRepositionMode = intent.mode();
    vehicleRepositionUntil = intent.until();
  }

  private static TransferPlanDraft validateDraft(TransferPlanDraft draft, boolean confirmation) {
    if (draft == null) throw new IllegalArgumentException("Transfer plan is required");
    List<TransferPlanDraft.CargoGroup> groups = copy(draft.cabinGroups());
    List<TransferPlanDraft.LooseFurniture> loose = copy(draft.looseFurniture());
    if (groups.size() > 100 || loose.size() > 100) {
      throw new IllegalArgumentException("Transfer cargo has too many requirement lines");
    }
    validateTimes(draft.plannedDepartureAt(), draft.plannedArrivalAt());
    TransferPlanDraft.ResourceIntent driverIntent =
        validateIntent(draft.driverReposition(), draft.plannedArrivalAt(), "driver");
    TransferPlanDraft.ResourceIntent vehicleIntent =
        validateIntent(draft.vehicleReposition(), draft.plannedArrivalAt(), "vehicle");
    if (confirmation
        && (draft.plannedDepartureAt() == null || draft.plannedArrivalAt() == null)) {
      throw new IllegalArgumentException(
          "Confirmed transfer requires planned departure and arrival instants");
    }

    Set<UUID> allocatedAssets = new HashSet<>();
    int cabinCount = 0;
    for (TransferPlanDraft.CargoGroup group : groups) {
      validateGroup(group, confirmation, allocatedAssets);
      cabinCount = Math.addExact(cabinCount, group.quantity());
    }
    Set<UUID> looseCatalogIds = new HashSet<>();
    for (TransferPlanDraft.LooseFurniture item : loose) {
      if (item == null
          || item.furnitureCatalogItemId() == null
          || item.quantity() < 1
          || !looseCatalogIds.add(item.furnitureCatalogItemId())) {
        throw new IllegalArgumentException("Loose furniture cargo is invalid or duplicated");
      }
    }
    int auditLines = Math.addExact(cabinCount, loose.size());
    boolean resourceIntent =
        draft.tripDriverId() != null
            || draft.tripVehicleId() != null
            || driverIntent.mode() != TransferResourceRepositionMode.NONE
            || vehicleIntent.mode() != TransferResourceRepositionMode.NONE;
    if ((confirmation && auditLines < 1 && !resourceIntent) || auditLines > 100) {
      throw new IllegalArgumentException("Transfer must contain 1 to 100 planned cargo lines");
    }
    return new TransferPlanDraft(
        draft.plannedDepartureAt(),
        draft.plannedArrivalAt(),
        normalizeComment(draft.logisticsComment()),
        draft.tripDriverId(),
        draft.tripVehicleId(),
        driverIntent,
        vehicleIntent,
        groups,
        loose);
  }

  private static void validateGroup(
      TransferPlanDraft.CargoGroup group, boolean confirmation, Set<UUID> allocatedAssets) {
    if (group == null || group.rentalTypeId() == null || group.quantity() < 1) {
      throw new IllegalArgumentException("Transfer cabin group is invalid");
    }
    List<UUID> characteristics = copy(group.characteristicIds());
    if (characteristics.size() > 100
        || characteristics.stream().anyMatch(Objects::isNull)
        || new HashSet<>(characteristics).size() != characteristics.size()) {
      throw new IllegalArgumentException("Transfer cabin characteristics are invalid or duplicated");
    }
    List<TransferPlanDraft.Furniture> furniture = copy(group.furniturePerCabin());
    if (furniture.size() > 100) {
      throw new IllegalArgumentException("Transfer cabin furniture has too many lines");
    }
    Set<UUID> furnitureIds = new HashSet<>();
    for (TransferPlanDraft.Furniture item : furniture) {
      if (item == null
          || item.furnitureCatalogItemId() == null
          || item.quantityPerCabin() < 1
          || !furnitureIds.add(item.furnitureCatalogItemId())) {
        throw new IllegalArgumentException("Per-cabin furniture is invalid or duplicated");
      }
      Math.multiplyExact(item.quantityPerCabin(), group.quantity());
    }
    List<TransferPlanDraft.Allocation> allocations = copy(group.allocatedCabins());
    if (allocations.size() > group.quantity() || (confirmation && allocations.size() != group.quantity())) {
      throw new IllegalArgumentException("Allocated cabin count must match the group quantity");
    }
    for (TransferPlanDraft.Allocation allocation : allocations) {
      if (allocation == null
          || allocation.assetId() == null
          || allocation.assetVersion() < 0
          || !allocatedAssets.add(allocation.assetId())) {
        throw new IllegalArgumentException("Allocated cabin is invalid or duplicated");
      }
    }
  }

  private static void validateTimes(OffsetDateTime departure, OffsetDateTime arrival) {
    if (arrival != null && departure == null) {
      throw new IllegalArgumentException("Planned arrival requires a planned departure");
    }
    if (arrival != null && !arrival.isAfter(departure)) {
      throw new IllegalArgumentException("Planned arrival must be after departure");
    }
  }

  private static TransferPlanDraft.ResourceIntent validateIntent(
      TransferPlanDraft.ResourceIntent intent, OffsetDateTime arrival, String resourceName) {
    TransferPlanDraft.ResourceIntent value =
        intent == null
            ? new TransferPlanDraft.ResourceIntent(
                null, TransferResourceRepositionMode.NONE, null)
            : intent;
    if (value.mode() == null) {
      throw new IllegalArgumentException("Transfer " + resourceName + " reposition mode is required");
    }
    switch (value.mode()) {
      case NONE -> {
        if (value.resourceId() != null || value.until() != null) {
          throw new IllegalArgumentException(
              "Transfer " + resourceName + " NONE intent cannot retain assignment data");
        }
      }
      case TEMPORARY -> {
        if (value.resourceId() == null
            || value.until() == null
            || (arrival != null && !value.until().isAfter(arrival))) {
          throw new IllegalArgumentException(
              "Temporary " + resourceName + " assignment must end after arrival");
        }
      }
      case PERMANENT -> {
        if (value.resourceId() == null || value.until() != null) {
          throw new IllegalArgumentException(
              "Permanent " + resourceName + " assignment has invalid timing");
        }
      }
    }
    return value;
  }

  private static String normalizeComment(String value) {
    if (value == null) return null;
    String normalized = value.trim();
    if (normalized.isEmpty()) return null;
    if (normalized.length() > 2_000) {
      throw new IllegalArgumentException("Logistics comment is too long");
    }
    return normalized;
  }

  private TransferLooseFurniture looseFurniture(int position) {
    return looseFurniture.stream()
        .filter(item -> item.getPosition() == position)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Loose furniture position is unknown"));
  }

  private static UUID stableAssignmentId(UUID current, UUID next) {
    UUID required = Objects.requireNonNull(next, "assignmentId");
    if (current != null && !current.equals(required)) {
      throw new IllegalStateException("Operational assignment identity changed");
    }
    return required;
  }

  private static Long monotonicVersion(Long current, long next) {
    if (next < 0 || current != null && next < current) {
      throw new IllegalArgumentException("Operational assignment version regressed");
    }
    return next;
  }

  private static String assignmentStatus(String value) {
    if (value == null || value.isBlank() || value.length() > 16) {
      throw new IllegalArgumentException("Operational assignment status is invalid");
    }
    return value;
  }

  private static String requireFailureCode(String value) {
    if (value == null || value.isBlank() || value.length() > 96) {
      throw new IllegalArgumentException("Transfer workflow failure code is invalid");
    }
    return value;
  }

  private static <T> List<T> copy(List<T> values) {
    return values == null ? List.of() : List.copyOf(values);
  }

  @PrePersist
  void prePersist() {
    OffsetDateTime now = currentTime();
    if (createdAt == null) createdAt = now;
    if (updatedAt == null) updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    touch();
  }

  private void touch() {
    OffsetDateTime now = currentTime();
    updatedAt =
        updatedAt != null && !now.isAfter(updatedAt) ? updatedAt.plus(1, ChronoUnit.MICROS) : now;
  }

  private static OffsetDateTime currentTime() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
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
    return thisClass == otherClass && id != null && Objects.equals(id, ((TransferPlan) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}

/** Persisted requirement group within one transfer plan. */
@Entity
@Table(
    name = "transfer_cargo_group",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_transfer_cargo_group_position",
            columnNames = {"plan_id", "position"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class TransferCargoGroup {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "plan_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_transfer_cargo_group_plan"))
  private TransferPlan plan;

  @Min(1)
  @Column(name = "position", nullable = false)
  private int position;

  @NotNull
  @Column(name = "rental_type_id", nullable = false)
  private UUID rentalTypeId;

  @Column(name = "dimension_id")
  private UUID dimensionId;

  @Column(name = "finishing_id")
  private UUID finishingId;

  @Column(name = "linoleum")
  private Boolean linoleum;

  @Min(1)
  @Column(name = "quantity", nullable = false)
  private int quantity;

  @ElementCollection(fetch = FetchType.LAZY)
  @CollectionTable(
      name = "transfer_cargo_group_characteristic",
      joinColumns = @JoinColumn(name = "group_id", nullable = false),
      foreignKey = @ForeignKey(name = "fk_transfer_cargo_group_characteristic_group"),
      uniqueConstraints =
          @UniqueConstraint(
              name = "uk_transfer_cargo_group_characteristic",
              columnNames = {"group_id", "characteristic_id"}))
  @Column(name = "characteristic_id", nullable = false)
  private Set<UUID> characteristicIds = new LinkedHashSet<>();

  @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("position ASC")
  private List<TransferCargoFurniture> furniture = new ArrayList<>();

  @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("position ASC")
  private List<TransferCargoAllocation> allocations = new ArrayList<>();

  static TransferCargoGroup create(
      TransferPlan plan, int position, TransferPlanDraft.CargoGroup draft) {
    TransferCargoGroup group = new TransferCargoGroup();
    group.plan = plan;
    group.position = position;
    group.rentalTypeId = draft.rentalTypeId();
    group.dimensionId = draft.dimensionId();
    group.finishingId = draft.finishingId();
    group.linoleum = draft.linoleum();
    group.quantity = draft.quantity();
    group.characteristicIds.addAll(draft.characteristicIds());
    for (int index = 0; index < draft.furniturePerCabin().size(); index++) {
      group.furniture.add(
          TransferCargoFurniture.create(group, index + 1, draft.furniturePerCabin().get(index)));
    }
    for (int index = 0; index < draft.allocatedCabins().size(); index++) {
      group.allocations.add(
          TransferCargoAllocation.create(group, index + 1, draft.allocatedCabins().get(index)));
    }
    return group;
  }

  TransferPlanDraft.CargoGroup toDraft() {
    return new TransferPlanDraft.CargoGroup(
        rentalTypeId,
        dimensionId,
        finishingId,
        characteristicIds.stream().sorted(Comparator.comparing(UUID::toString)).toList(),
        linoleum,
        quantity,
        furniture.stream().map(TransferCargoFurniture::toDraft).toList(),
        allocations.stream().map(TransferCargoAllocation::toDraft).toList());
  }

  TransferPlanSnapshot.CargoGroup snapshot() {
    return new TransferPlanSnapshot.CargoGroup(
        id,
        position,
        rentalTypeId,
        dimensionId,
        finishingId,
        characteristicIds.stream().sorted(Comparator.comparing(UUID::toString)).toList(),
        linoleum,
        quantity,
        furniture.stream().map(item -> item.snapshot(quantity)).toList(),
        allocations.stream().map(TransferCargoAllocation::snapshot).toList());
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
        && Objects.equals(id, ((TransferCargoGroup) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}

/** Persisted per-cabin furniture requirement within a transfer cargo group. */
@Entity
@Table(
    name = "transfer_cargo_group_furniture",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_transfer_cargo_group_furniture_position",
          columnNames = {"group_id", "position"}),
      @UniqueConstraint(
          name = "uk_transfer_cargo_group_furniture_catalog",
          columnNames = {"group_id", "furniture_catalog_item_id"})
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class TransferCargoFurniture {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "group_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_transfer_cargo_group_furniture_group"))
  private TransferCargoGroup group;

  @Min(1)
  @Column(name = "position", nullable = false)
  private int position;

  @NotNull
  @Column(name = "furniture_catalog_item_id", nullable = false)
  private UUID furnitureCatalogItemId;

  @Min(1)
  @Column(name = "quantity_per_cabin", nullable = false)
  private long quantityPerCabin;

  static TransferCargoFurniture create(
      TransferCargoGroup group, int position, TransferPlanDraft.Furniture draft) {
    TransferCargoFurniture item = new TransferCargoFurniture();
    item.group = group;
    item.position = position;
    item.furnitureCatalogItemId = draft.furnitureCatalogItemId();
    item.quantityPerCabin = draft.quantityPerCabin();
    return item;
  }

  TransferPlanDraft.Furniture toDraft() {
    return new TransferPlanDraft.Furniture(furnitureCatalogItemId, quantityPerCabin);
  }

  TransferPlanSnapshot.Furniture snapshot(int cabinQuantity) {
    return new TransferPlanSnapshot.Furniture(
        furnitureCatalogItemId,
        quantityPerCabin,
        Math.multiplyExact(quantityPerCabin, cabinQuantity));
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
        && Objects.equals(id, ((TransferCargoFurniture) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}

/** Persisted concrete cabin allocation associated with one requirement group. */
@Entity
@Table(
    name = "transfer_cargo_group_allocation",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_transfer_cargo_group_allocation_position",
          columnNames = {"group_id", "position"}),
      @UniqueConstraint(
          name = "uk_transfer_cargo_group_allocation_asset",
          columnNames = {"group_id", "asset_id"})
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class TransferCargoAllocation {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "group_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_transfer_cargo_group_allocation_group"))
  private TransferCargoGroup group;

  @Min(1)
  @Column(name = "position", nullable = false)
  private int position;

  @NotNull
  @Column(name = "asset_id", nullable = false)
  private UUID assetId;

  @Min(0)
  @Column(name = "asset_version", nullable = false)
  private long assetVersion;

  static TransferCargoAllocation create(
      TransferCargoGroup group, int position, TransferPlanDraft.Allocation draft) {
    TransferCargoAllocation allocation = new TransferCargoAllocation();
    allocation.group = group;
    allocation.position = position;
    allocation.assetId = draft.assetId();
    allocation.assetVersion = draft.assetVersion();
    return allocation;
  }

  TransferPlanDraft.Allocation toDraft() {
    return new TransferPlanDraft.Allocation(assetId, assetVersion);
  }

  TransferPlanSnapshot.Allocation snapshot() {
    return new TransferPlanSnapshot.Allocation(assetId, assetVersion);
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
        && Objects.equals(id, ((TransferCargoAllocation) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}

/** Persisted furniture cargo transported independently from cabin composition. */
@Entity
@Table(
    name = "transfer_loose_furniture",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_transfer_loose_furniture_position",
          columnNames = {"plan_id", "position"}),
      @UniqueConstraint(
          name = "uk_transfer_loose_furniture_catalog",
          columnNames = {"plan_id", "furniture_catalog_item_id"})
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class TransferLooseFurniture {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "plan_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_transfer_loose_furniture_plan"))
  private TransferPlan plan;

  @Min(1)
  @Column(name = "position", nullable = false)
  private int position;

  @NotNull
  @Column(name = "furniture_catalog_item_id", nullable = false)
  private UUID furnitureCatalogItemId;

  @Min(1)
  @Column(name = "quantity", nullable = false)
  private long quantity;

  @Column(name = "source_balance_id")
  private UUID sourceBalanceId;

  @Column(name = "expected_source_balance_version")
  private Long expectedSourceBalanceVersion;

  @Column(name = "reservation_id")
  private UUID reservationId;

  @Column(name = "reservation_version")
  private Long reservationVersion;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "reservation_state", nullable = false, length = 32)
  private TransferLooseFurnitureState reservationState;

  static TransferLooseFurniture create(
      TransferPlan plan, int position, TransferPlanDraft.LooseFurniture draft) {
    TransferLooseFurniture item = new TransferLooseFurniture();
    item.plan = plan;
    item.position = position;
    item.furnitureCatalogItemId = draft.furnitureCatalogItemId();
    item.quantity = draft.quantity();
    item.reservationState = TransferLooseFurnitureState.PENDING;
    return item;
  }

  void freezeSource(UUID balanceId, long expectedBalanceVersion) {
    UUID required = Objects.requireNonNull(balanceId, "balanceId");
    if (expectedBalanceVersion < 0 || reservationState != TransferLooseFurnitureState.PENDING) {
      throw new IllegalStateException("Loose furniture source can no longer be selected");
    }
    if (sourceBalanceId != null && !sourceBalanceId.equals(required)) {
      throw new IllegalStateException("Loose furniture source balance changed");
    }
    if (expectedSourceBalanceVersion != null
        && expectedSourceBalanceVersion != expectedBalanceVersion) {
      throw new IllegalStateException("Loose furniture source version changed");
    }
    sourceBalanceId = required;
    expectedSourceBalanceVersion = expectedBalanceVersion;
  }

  void attachReservation(
      UUID nextReservationId, long nextReservationVersion, UUID confirmedSourceBalanceId) {
    UUID required = Objects.requireNonNull(nextReservationId, "reservationId");
    if (nextReservationVersion < 0
        || sourceBalanceId == null
        || !sourceBalanceId.equals(confirmedSourceBalanceId)) {
      throw new IllegalArgumentException("Loose furniture reservation receipt is invalid");
    }
    if (reservationId != null && !reservationId.equals(required)) {
      throw new IllegalStateException("Loose furniture reservation identity changed");
    }
    if (reservationVersion != null && nextReservationVersion < reservationVersion) {
      throw new IllegalArgumentException("Loose furniture reservation version regressed");
    }
    reservationId = required;
    reservationVersion = nextReservationVersion;
    reservationState = TransferLooseFurnitureState.RESERVED;
  }

  void markInTransit() {
    if (reservationState == TransferLooseFurnitureState.RESERVED) {
      reservationState = TransferLooseFurnitureState.IN_TRANSIT;
    }
  }

  void release(long nextReservationVersion) {
    requireReservationVersion(nextReservationVersion);
    reservationVersion = nextReservationVersion;
    reservationState = TransferLooseFurnitureState.RELEASED;
  }

  void execute(long nextReservationVersion) {
    requireReservationVersion(nextReservationVersion);
    reservationVersion = nextReservationVersion;
    reservationState = TransferLooseFurnitureState.EXECUTED;
  }

  private void requireReservationVersion(long nextReservationVersion) {
    if (reservationId == null
        || reservationVersion == null
        || nextReservationVersion < reservationVersion) {
      throw new IllegalArgumentException("Loose furniture reservation result is invalid");
    }
  }

  TransferPlanDraft.LooseFurniture toDraft() {
    return new TransferPlanDraft.LooseFurniture(furnitureCatalogItemId, quantity);
  }

  TransferPlanSnapshot.LooseFurniture snapshot() {
    return new TransferPlanSnapshot.LooseFurniture(
        id,
        position,
        furnitureCatalogItemId,
        quantity,
        sourceBalanceId,
        expectedSourceBalanceVersion,
        reservationId,
        reservationVersion,
        reservationState);
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
        && Objects.equals(id, ((TransferLooseFurniture) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
