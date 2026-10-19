package dev.buhanzaz.rwms.logistics.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

/**
 * Permanent receipt for one inventory-service command, including its immutable request fingerprint
 * and frozen successful response.
 */
@Entity
@Table(name = "inventory_outcome_receipt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InventoryOutcomeReceipt {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

  @Column(name = "inventory_id", nullable = false)
  private UUID inventoryId;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "inventory_completed_at", nullable = false)
  private OffsetDateTime inventoryCompletedAt;

  @Column(name = "final_plan_version", nullable = false)
  private long finalPlanVersion;

  @JdbcTypeCode(Types.CHAR)
  @Column(name = "final_plan_sha256", nullable = false, length = 64)
  private String finalPlanSha256;

  @JdbcTypeCode(Types.CHAR)
  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "request_json", nullable = false, columnDefinition = "jsonb")
  private JsonNode requestJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "superseded_document_ids", nullable = false, columnDefinition = "jsonb")
  private JsonNode supersededDocumentIds;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "superseded_rental_order_ids", nullable = false, columnDefinition = "jsonb")
  private JsonNode supersededRentalOrderIds;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "response_json", columnDefinition = "jsonb")
  private JsonNode responseJson;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 24)
  private InventoryOutcomeReceiptState state;

  @Column(name = "superseded_line_count", nullable = false)
  private long supersededLineCount;

  @Column(name = "superseded_rental_unit_count", nullable = false)
  private long supersededRentalUnitCount;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @Column(name = "completed_at")
  private OffsetDateTime completedAt;

  /** Creates the durable boundary before any remote task cancellation is attempted. */
  public static InventoryOutcomeReceipt prepare(
      UUID idempotencyKey,
      UUID inventoryId,
      UUID warehouseId,
      OffsetDateTime inventoryCompletedAt,
      long finalPlanVersion,
      String finalPlanSha256,
      String requestSha256,
      JsonNode requestJson,
      JsonNode supersededDocumentIds,
      JsonNode supersededRentalOrderIds,
      long supersededLineCount,
      long supersededRentalUnitCount) {
    InventoryOutcomeReceipt receipt = new InventoryOutcomeReceipt();
    receipt.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    receipt.inventoryId = Objects.requireNonNull(inventoryId, "inventoryId");
    receipt.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
    receipt.inventoryCompletedAt =
        Objects.requireNonNull(inventoryCompletedAt, "inventoryCompletedAt");
    receipt.finalPlanVersion = requireVersion(finalPlanVersion);
    receipt.finalPlanSha256 = requireHash(finalPlanSha256, "finalPlanSha256");
    receipt.requestSha256 = requireHash(requestSha256, "requestSha256");
    receipt.requestJson = Objects.requireNonNull(requestJson, "requestJson");
    receipt.supersededDocumentIds =
        Objects.requireNonNull(supersededDocumentIds, "supersededDocumentIds");
    receipt.supersededRentalOrderIds =
        Objects.requireNonNull(supersededRentalOrderIds, "supersededRentalOrderIds");
    if (supersededLineCount < 0 || supersededRentalUnitCount < 0) {
      throw new IllegalArgumentException("Inventory outcome counts cannot be negative");
    }
    receipt.supersededLineCount = supersededLineCount;
    receipt.supersededRentalUnitCount = supersededRentalUnitCount;
    receipt.state = InventoryOutcomeReceiptState.PREPARED;
    receipt.createdAt = now();
    receipt.updatedAt = receipt.createdAt;
    return receipt;
  }

  public boolean matchesRequest(String fingerprint) {
    return requestSha256.equals(fingerprint);
  }

  /** Freezes the response once every owned remote/local effect has a terminal checkpoint. */
  public void complete(JsonNode response) {
    if (state == InventoryOutcomeReceiptState.COMPLETED) {
      if (!Objects.equals(responseJson, response)) {
        throw new IllegalStateException("Inventory outcome response is already frozen");
      }
      return;
    }
    responseJson = Objects.requireNonNull(response, "response");
    state = InventoryOutcomeReceiptState.COMPLETED;
    completedAt = now();
    updatedAt = completedAt;
  }

  private static long requireVersion(long value) {
    if (value < 1) throw new IllegalArgumentException("finalPlanVersion must be positive");
    return value;
  }

  private static String requireHash(String value, String field) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return value;
  }

  private static OffsetDateTime now() {
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
    return thisClass == otherClass
        && id != null
        && Objects.equals(id, ((InventoryOutcomeReceipt) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
