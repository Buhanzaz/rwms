package dev.buhanzaz.rwms.logistics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

/** Immutable logistics evidence sent to the maintenance-owned shortage source. */
@Entity
@Table(
    name = "logistics_return_shortage_snapshot",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_logistics_return_shortage_snapshot_line",
            columnNames = "line_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LogisticsReturnShortageSnapshot {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "document_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_logistics_return_shortage_snapshot_document"))
  private LogisticsDocument document;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "line_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_logistics_return_shortage_snapshot_line"))
  private LogisticsDocumentLine line;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "rental_item_id", nullable = false)
  private UUID rentalItemId;

  @Column(name = "rental_item_version", nullable = false)
  private long rentalItemVersion;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "shortages", nullable = false, columnDefinition = "jsonb")
  private JsonNode shortages;

  @JdbcTypeCode(Types.CHAR)
  @Column(name = "snapshot_sha256", nullable = false, length = 64)
  private String snapshotSha256;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  public static LogisticsReturnShortageSnapshot create(
      LogisticsDocument document,
      LogisticsDocumentLine line,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      JsonNode shortages,
      String snapshotSha256,
      OffsetDateTime createdAt) {
    if (document == null
        || line == null
        || warehouseId == null
        || rentalItemId == null
        || createdAt == null) {
      throw new IllegalArgumentException("Shortage snapshot ownership and timing are required");
    }
    if (rentalItemVersion < 0) throw new IllegalArgumentException("Rental-item version is invalid");
    if (shortages == null || !shortages.isObject()) {
      throw new IllegalArgumentException("Shortages must be a JSON object");
    }
    if (snapshotSha256 == null || !snapshotSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Shortage snapshot digest is invalid");
    }
    LogisticsReturnShortageSnapshot snapshot = new LogisticsReturnShortageSnapshot();
    snapshot.document = document;
    snapshot.line = line;
    snapshot.warehouseId = warehouseId;
    snapshot.rentalItemId = rentalItemId;
    snapshot.rentalItemVersion = rentalItemVersion;
    snapshot.shortages = shortages.deepCopy();
    snapshot.snapshotSha256 = snapshotSha256;
    snapshot.createdAt = createdAt;
    return snapshot;
  }
}
