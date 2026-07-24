package dev.buhanzaz.rwms.logistics.domain;

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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

@Entity
@Table(name = "logistics_document_line")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LogisticsDocumentLine {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "document_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_logistics_document_line_document"))
  private LogisticsDocument document;

  @Column(name = "line_number", nullable = false)
  private int lineNumber;

  @Column(name = "asset_id", nullable = false)
  private UUID assetId;

  @Column(name = "asset_version", nullable = false)
  private long assetVersion;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 16)
  private LogisticsLineState state;

  @Column(name = "tenant_snapshot", length = 512)
  private String tenantSnapshot;

  /** Opaque existing rental-order reference used to prove client ownership. */
  @Column(name = "rental_order_id")
  private UUID rentalOrderId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "passport_snapshot", columnDefinition = "jsonb")
  private JsonNode passportSnapshot;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "expected_contents_snapshot", columnDefinition = "jsonb")
  private JsonNode expectedContentsSnapshot;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "factual_contents_snapshot", columnDefinition = "jsonb")
  private JsonNode factualContentsSnapshot;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "source_allocation_snapshot", columnDefinition = "jsonb")
  private JsonNode sourceAllocationSnapshot;

  /**
   * Furniture found in addition to the canonical cabin contents at return
   * acceptance.  The logistics completion workflow turns this immutable fact
   * into an asset-service stock receipt.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "return_additional_contents_snapshot", columnDefinition = "jsonb")
  private JsonNode returnAdditionalContentsSnapshot;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public static LogisticsDocumentLine create(
      LogisticsDocument document,
      int lineNumber,
      UUID assetId,
      long assetVersion,
      String tenantSnapshot) {
    return create(document, lineNumber, assetId, assetVersion, tenantSnapshot, null);
  }

  public static LogisticsDocumentLine create(
      LogisticsDocument document,
      int lineNumber,
      UUID assetId,
      long assetVersion,
      String tenantSnapshot,
      UUID rentalOrderId) {
    if (document == null) throw new IllegalArgumentException("document is required");
    if (lineNumber < 1) throw new IllegalArgumentException("lineNumber must be positive");
    if (assetId == null) throw new IllegalArgumentException("assetId is required");
    if (assetVersion < 0) throw new IllegalArgumentException("assetVersion must not be negative");
    LogisticsDocumentLine line = new LogisticsDocumentLine();
    line.document = document;
    line.lineNumber = lineNumber;
    line.assetId = assetId;
    line.assetVersion = assetVersion;
    line.state = LogisticsLineState.PENDING;
    line.tenantSnapshot = optionalSnapshot(tenantSnapshot);
    line.rentalOrderId = rentalOrderId;
    return line;
  }

  public void beginDeparture() {
    transition(LogisticsLineState.PENDING, LogisticsLineState.DEPARTING);
  }

  public void markDeparted() {
    transition(LogisticsLineState.DEPARTING, LogisticsLineState.DEPARTED);
  }

  public void beginArrival() {
    transition(LogisticsLineState.DEPARTED, LogisticsLineState.ARRIVING);
  }

  public void markArrived() {
    transition(LogisticsLineState.ARRIVING, LogisticsLineState.ARRIVED);
  }

  public void conflict() {
    if (state == LogisticsLineState.ARRIVED || state == LogisticsLineState.CANCELLED) {
      throw new IllegalStateException("Terminal line cannot enter conflict");
    }
    state = LogisticsLineState.CONFLICT;
  }

  public void cancel() {
    if (state != LogisticsLineState.PENDING) {
      throw new IllegalStateException("Only a pending line can be cancelled");
    }
    state = LogisticsLineState.CANCELLED;
  }

  /** Refreshes the mutable draft projection after asset-side order reservation. */
  public boolean synchronizeOrderReservation(long nextAssetVersion, String nextTenantSnapshot) {
    if (rentalOrderId == null || state != LogisticsLineState.PENDING) {
      throw new IllegalStateException("Only a pending rental-order line can be synchronized");
    }
    if (nextAssetVersion < assetVersion) {
      throw new IllegalArgumentException("assetVersion must not move backwards");
    }
    String requiredTenantSnapshot = optionalSnapshot(nextTenantSnapshot);
    if (requiredTenantSnapshot == null) {
      throw new IllegalArgumentException("tenantSnapshot is required");
    }
    if (assetVersion == nextAssetVersion
        && java.util.Objects.equals(tenantSnapshot, requiredTenantSnapshot)) {
      return false;
    }
    assetVersion = nextAssetVersion;
    tenantSnapshot = requiredTenantSnapshot;
    return true;
  }

  public void captureExpectedContents(JsonNode snapshot) {
    expectedContentsSnapshot = captureImmutable(expectedContentsSnapshot, snapshot, "expectedContentsSnapshot");
  }

  public void captureFactualContents(JsonNode snapshot) {
    factualContentsSnapshot = captureImmutable(factualContentsSnapshot, snapshot, "factualContentsSnapshot");
  }

  public void captureSourceAllocations(JsonNode snapshot) {
    sourceAllocationSnapshot =
        captureImmutable(sourceAllocationSnapshot, snapshot, "sourceAllocationSnapshot");
  }

  public void captureReturnAdditionalContents(JsonNode snapshot) {
    returnAdditionalContentsSnapshot =
        captureImmutable(
            returnAdditionalContentsSnapshot, snapshot, "returnAdditionalContentsSnapshot");
  }

  @PrePersist
  void beforeInsert() {
    OffsetDateTime now = currentTime();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void beforeUpdate() {
    updatedAt = currentTime();
  }

  private void transition(LogisticsLineState expected, LogisticsLineState next) {
    if (state != expected) throw new IllegalStateException("Line lifecycle transition is not allowed");
    state = next;
  }

  private static String optionalSnapshot(String value) {
    if (value == null) return null;
    String normalized = value.trim();
    if (normalized.isEmpty()) return null;
    if (normalized.length() > 512) throw new IllegalArgumentException("tenantSnapshot is too long");
    return normalized;
  }

  private static JsonNode captureImmutable(JsonNode current, JsonNode incoming, String field) {
    if (incoming == null || !incoming.isObject()) {
      throw new IllegalArgumentException(field + " must be a JSON object");
    }
    if (current != null && !current.equals(incoming)) {
      throw new IllegalStateException(field + " is immutable once captured");
    }
    return incoming.deepCopy();
  }

  private static OffsetDateTime currentTime() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }
}
