package dev.buhanzaz.rwms.asset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.hibernate.proxy.HibernateProxy;

/**
 * JPA entity that persists rental item in the asset-owned database.
 */
@Entity
@Table(name = "rental_item")
public class RentalItem {
  private static final Locale NUMBER_LOCALE = Locale.forLanguageTag("ru-RU");
  private static final String NEW_CATEGORY = "Новая";
  private static final Pattern DISPLAY_NUMBER = Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N} _-]{0,127}$");
  private static final Pattern MATCH_KEY = Pattern.compile("^[\\p{L}\\p{N}]{1,128}$");

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "display_canonical_number", nullable = false, length = 128)
  private String number;

  @Column(name = "identity_match_key", nullable = false, length = 128)
  private String identityMatchKey;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 64)
  private RentalItemStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "transfer_origin_status", length = 64)
  private RentalItemStatus transferOriginStatus;

  @Column(name = "cabin_type_id")
  private UUID rentalTypeId;

  @Column(name = "cabin_dimension_id")
  private UUID dimensionId;

  @Column(name = "cabin_finishing_id")
  private UUID finishingId;

  @Column(name = "cabin_category_id")
  private UUID categoryId;

  @Column(name = "category", length = 255)
  private String category;

  @Column(name = "linoleum")
  private Boolean linoleum;

  @Column(name = "general_comment", length = 4000)
  private String generalComment;

  @Column(name = "passport_json", nullable = false, length = 16000)
  private String passportJson = "{}";

  @Column(name = "tags_json", nullable = false, length = 8000)
  private String tagsJson = "[]";

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected RentalItem() {}

  public static RentalItem create(
      UUID warehouseId,
      String number,
      UUID rentalTypeId,
      UUID dimensionId,
      UUID finishingId,
      String category,
      Boolean linoleum,
      String passportJson,
      String tagsJson) {
    if (warehouseId == null) throw new IllegalArgumentException("warehouseId is required");
    String creationCategory =
        category == null || category.isBlank() ? NEW_CATEGORY : category;
    return createWithStatus(
        warehouseId,
        number,
        RentalItemStatus.FREE,
        rentalTypeId,
        dimensionId,
        finishingId,
        null,
        creationCategory,
        linoleum,
        passportJson,
        tagsJson);
  }

  public static RentalItem create(
      UUID warehouseId,
      String number,
      UUID rentalTypeId,
      UUID dimensionId,
      UUID finishingId,
      UUID categoryId,
      String category,
      Boolean linoleum,
      String passportJson,
      String tagsJson) {
    String creationCategory =
        category == null || category.isBlank() ? NEW_CATEGORY : category;
    return createWithStatus(
        warehouseId,
        number,
        RentalItemStatus.FREE,
        rentalTypeId,
        dimensionId,
        finishingId,
        categoryId,
        creationCategory,
        linoleum,
        passportJson,
        tagsJson);
  }

  public static RentalItem createFromInventory(
      UUID warehouseId,
      String number,
      UUID rentalTypeId,
      UUID dimensionId,
      UUID finishingId,
      String category,
      Boolean linoleum,
      String passportJson,
      String tagsJson) {
    return createWithStatus(
        warehouseId,
        number,
        RentalItemStatus.FREE,
        rentalTypeId,
        dimensionId,
        finishingId,
        null,
        category,
        linoleum,
        passportJson,
        tagsJson);
  }

  public static RentalItem createFromInventory(
      UUID warehouseId,
      String number,
      UUID rentalTypeId,
      UUID dimensionId,
      UUID finishingId,
      UUID categoryId,
      String category,
      Boolean linoleum,
      String passportJson,
      String tagsJson) {
    return createWithStatus(
        warehouseId,
        number,
        RentalItemStatus.FREE,
        rentalTypeId,
        dimensionId,
        finishingId,
        categoryId,
        category,
        linoleum,
        passportJson,
        tagsJson);
  }

  public static RentalItem createFromHtmlImport(
      UUID warehouseId,
      String number,
      RentalItemStatus status,
      UUID rentalTypeId,
      UUID dimensionId,
      UUID finishingId,
      UUID categoryId,
      String category,
      Boolean linoleum,
      String passportJson,
      String tagsJson) {
    if (status == RentalItemStatus.IN_TRANSFER || status.isTerminalDispositionStatus()) {
      throw new IllegalArgumentException("HTML import cannot create a fenced or terminal status");
    }
    return createHtmlImportItem(
        warehouseId,
        number,
        status,
        rentalTypeId,
        dimensionId,
        finishingId,
        categoryId,
        category,
        linoleum,
        passportJson,
        tagsJson);
  }

  public boolean changePassport(
      UUID rentalTypeId,
      UUID dimensionId,
      UUID finishingId,
      String category,
      Boolean linoleum,
      String passportJson,
      String tagsJson) {
    return changePassport(
        rentalTypeId,
        dimensionId,
        finishingId,
        null,
        category,
        linoleum,
        passportJson,
        tagsJson);
  }

  public boolean changePassport(
      UUID rentalTypeId,
      UUID dimensionId,
      UUID finishingId,
      UUID categoryId,
      String category,
      Boolean linoleum,
      String passportJson,
      String tagsJson) {
    requireCabinCompositionIds(rentalTypeId, dimensionId, finishingId);
    String nextCategory = optional(category, 255);
    String nextPassport = jsonObject(passportJson);
    String nextTags = jsonArray(tagsJson);
    if (Objects.equals(this.rentalTypeId, rentalTypeId)
        && Objects.equals(this.dimensionId, dimensionId)
        && Objects.equals(this.finishingId, finishingId)
        && Objects.equals(this.categoryId, categoryId)
        && Objects.equals(this.category, nextCategory)
        && Objects.equals(this.linoleum, linoleum)
        && Objects.equals(this.passportJson, nextPassport)
        && Objects.equals(this.tagsJson, nextTags)) return false;
    this.rentalTypeId = rentalTypeId;
    this.dimensionId = dimensionId;
    this.finishingId = finishingId;
    this.categoryId = categoryId;
    this.category = nextCategory;
    this.linoleum = linoleum;
    this.passportJson = nextPassport;
    this.tagsJson = nextTags;
    return true;
  }

  public boolean changeStatus(RentalItemStatus next) {
    if (next == null) throw new IllegalArgumentException("status is required");
    if (!status.acceptsManualStatusChangeTo(next)) {
      throw new IllegalStateException("Rental-item status is fenced or terminal");
    }
    if (status == next) return false;
    status = next;
    return true;
  }

  /**
   * A service-to-service operation that holds the current operation lease may
   * perform a fenced status transition, including terminal disposition states.
   * The caller must validate the lease before invoking this transition.
   */
  public boolean changeStatusUnderLease(RentalItemStatus next) {
    if (next == null) throw new IllegalArgumentException("status is required");
    if (status.isTerminalDispositionStatus() && next != status) {
      throw new IllegalStateException("Terminal rental item cannot change status");
    }
    if (status == RentalItemStatus.IN_TRANSFER || next == RentalItemStatus.IN_TRANSFER) {
      throw new IllegalStateException(
          "Transfer status must use the dedicated transfer transition");
    }
    if (status == next) return false;
    status = next;
    return true;
  }

  /**
   * Applies the latest completed-inventory truth after the owning service has superseded all live
   * operational bindings. Terminal loss and write-off remain irreversible.
   */
  public boolean applyCompletedInventoryOutcome(RentalItemStatus next) {
    if (next != RentalItemStatus.FREE
        && next != RentalItemStatus.REPAIR
        && next != RentalItemStatus.CAPITAL_REPAIR) {
      throw new IllegalArgumentException("Completed inventory outcome status is invalid");
    }
    if (status.isTerminalDispositionStatus()) {
      throw new IllegalStateException("Terminal rental item cannot change status");
    }
    boolean changed = status != next || transferOriginStatus != null;
    status = next;
    transferOriginStatus = null;
    return changed;
  }

  /**
   * Applies the terminal cabin truth after the asset service has verified a
   * durable maintenance disposition fence.  This method deliberately accepts
   * only the two terminal dispositions; it does not create a generic status
   * transition path for callers that do not own the fence.
   */
  public boolean applyPropertyDisposition(RentalItemStatus terminalStatus) {
    if (terminalStatus == null || !terminalStatus.isTerminalDispositionStatus()) {
      throw new IllegalArgumentException("Property disposition must be terminal");
    }
    if (status.isTerminalDispositionStatus()) {
      if (status == terminalStatus) return false;
      throw new IllegalStateException("Terminal rental item cannot change disposition");
    }
    if (status == RentalItemStatus.IN_TRANSFER || transferOriginStatus != null) {
      throw new IllegalStateException("Transferred rental item cannot be disposed directly");
    }
    status = terminalStatus;
    return true;
  }

  public void departTransferUnderLease(RentalItemStatus restoredStatus) {
    if (restoredStatus != RentalItemStatus.FREE
        && restoredStatus != RentalItemStatus.REPAIR) {
      throw new IllegalArgumentException(
          "Transfer restoration status must be FREE or REPAIR");
    }
    boolean matchingFree =
        restoredStatus == RentalItemStatus.FREE
            && status == RentalItemStatus.FREE;
    boolean matchingRepair =
        restoredStatus == RentalItemStatus.REPAIR
            && (status == RentalItemStatus.REPAIR
                || status == RentalItemStatus.CAPITAL_REPAIR);
    if (!matchingFree && !matchingRepair) {
      throw new IllegalStateException(
          "Transfer restoration status does not match the current rental-item status");
    }
    if (transferOriginStatus != null) {
      throw new IllegalStateException("Rental item already has a transfer origin status");
    }
    transferOriginStatus = restoredStatus;
    status = RentalItemStatus.IN_TRANSFER;
  }

  public void arriveTransferUnderLease(RentalItemStatus restoredStatus) {
    if (status != RentalItemStatus.IN_TRANSFER
        || transferOriginStatus == null
        || transferOriginStatus != restoredStatus) {
      throw new IllegalStateException("Transfer origin status does not match the restored status");
    }
    status = restoredStatus;
    transferOriginStatus = null;
  }

  public boolean changeWarehouse(UUID nextWarehouseId) {
    if (nextWarehouseId == null) throw new IllegalArgumentException("warehouseId is required");
    if (warehouseId.equals(nextWarehouseId)) return false;
    if (status.isTerminalDispositionStatus() || status == RentalItemStatus.IN_TRANSFER) {
      throw new IllegalStateException("Rental item cannot change warehouse in its current status");
    }
    warehouseId = nextWarehouseId;
    return true;
  }

  public boolean changeGeneralComment(String value) {
    String normalized = optional(value, 4000);
    if (Objects.equals(generalComment, normalized)) return false;
    generalComment = normalized;
    return true;
  }

  /** Bumps the aggregate revision for append-only child facts. */
  public void touchActivity() {
    updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
  }

  private void assignPassport(
      UUID rentalTypeId,
      UUID dimensionId,
      UUID finishingId,
      UUID categoryId,
      String category,
      Boolean linoleum,
      String passportJson,
      String tagsJson) {
    requireCabinCompositionIds(rentalTypeId, dimensionId, finishingId);
    this.rentalTypeId = rentalTypeId;
    this.dimensionId = dimensionId;
    this.finishingId = finishingId;
    this.categoryId = categoryId;
    this.category = optional(category, 255);
    this.linoleum = linoleum;
    this.passportJson = jsonObject(passportJson);
    this.tagsJson = jsonArray(tagsJson);
  }

  /**
   * HTML can contain historically incomplete cabin records. This assignment path deliberately
   * preserves absent composition references so the import can create the cabin for later
   * completion in the regular editor.
   */
  private void assignHtmlImportPassport(
      UUID rentalTypeId,
      UUID dimensionId,
      UUID finishingId,
      UUID categoryId,
      String category,
      Boolean linoleum,
      String passportJson,
      String tagsJson) {
    this.rentalTypeId = rentalTypeId;
    this.dimensionId = dimensionId;
    this.finishingId = finishingId;
    this.categoryId = categoryId;
    this.category = optional(category, 255);
    this.linoleum = linoleum;
    this.passportJson = jsonObject(passportJson);
    this.tagsJson = jsonArray(tagsJson);
  }

  @PrePersist
  void prePersist() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    createdAt = now;
    updatedAt = now;
    number = canonicalNumber(number);
    identityMatchKey = identityMatchKey(number);
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    number = canonicalNumber(number);
    identityMatchKey = identityMatchKey(number);
  }

  public static String canonicalNumber(String value) {
    String candidate = value == null ? "" : value.strip().replaceAll("[\\p{Z}\\s]+", " ");
    String normalized = candidate.toUpperCase(NUMBER_LOCALE);
    if (!DISPLAY_NUMBER.matcher(normalized).matches()) {
      throw new IllegalArgumentException(
          "Rental item number must contain 1 to 128 letters, digits, spaces, ASCII hyphens, or underscores");
    }
    return normalized;
  }

  public static String identityMatchKey(String value) {
    String key = canonicalNumber(value).replace(" ", "").replace("-", "").replace("_", "");
    if (!MATCH_KEY.matcher(key).matches()) {
      throw new IllegalArgumentException("Rental item number has no identity characters");
    }
    return key;
  }

  private static RentalItem createWithStatus(
      UUID warehouseId,
      String number,
      RentalItemStatus status,
      UUID rentalTypeId,
      UUID dimensionId,
      UUID finishingId,
      UUID categoryId,
      String category,
      Boolean linoleum,
      String passportJson,
      String tagsJson) {
    if (warehouseId == null) throw new IllegalArgumentException("warehouseId is required");
    RentalItem item = new RentalItem();
    item.warehouseId = warehouseId;
    item.number = canonicalNumber(number);
    item.identityMatchKey = identityMatchKey(item.number);
    item.status = status;
    item.assignPassport(
        rentalTypeId,
        dimensionId,
        finishingId,
        categoryId,
        category,
        linoleum,
        passportJson,
        tagsJson);
    return item;
  }

  private static RentalItem createHtmlImportItem(
      UUID warehouseId,
      String number,
      RentalItemStatus status,
      UUID rentalTypeId,
      UUID dimensionId,
      UUID finishingId,
      UUID categoryId,
      String category,
      Boolean linoleum,
      String passportJson,
      String tagsJson) {
    if (warehouseId == null) throw new IllegalArgumentException("warehouseId is required");
    RentalItem item = new RentalItem();
    item.warehouseId = warehouseId;
    item.number = canonicalNumber(number);
    item.identityMatchKey = identityMatchKey(item.number);
    item.status = status;
    item.assignHtmlImportPassport(
        rentalTypeId,
        dimensionId,
        finishingId,
        categoryId,
        category,
        linoleum,
        passportJson,
        tagsJson);
    return item;
  }

  private static void requireCabinCompositionIds(
      UUID rentalTypeId, UUID dimensionId, UUID finishingId) {
    if (rentalTypeId == null || dimensionId == null || finishingId == null) {
      throw new IllegalArgumentException("Cabin composition IDs are required");
    }
  }

  private static String optional(String value, int maximum) {
    if (value == null) return null;
    String normalized = value.trim();
    if (normalized.isEmpty()) return null;
    if (normalized.length() > maximum) throw new IllegalArgumentException("Value is too long");
    return normalized;
  }

  private static String jsonObject(String value) {
    String normalized = value == null || value.isBlank() ? "{}" : value.trim();
    if (normalized.length() > 16000 || !normalized.startsWith("{") || !normalized.endsWith("}")) {
      throw new IllegalArgumentException("passport must be a bounded JSON object");
    }
    return normalized;
  }

  private static String jsonArray(String value) {
    String normalized = value == null || value.isBlank() ? "[]" : value.trim();
    if (normalized.length() > 8000 || !normalized.startsWith("[") || !normalized.endsWith("]")) {
      throw new IllegalArgumentException("tags must be a bounded JSON array");
    }
    return normalized;
  }

  public UUID getId() { return id; }
  public long getVersion() { return version; }
  public UUID getWarehouseId() { return warehouseId; }
  public String getNumber() { return number; }
  public String getIdentityMatchKey() { return identityMatchKey; }
  public RentalItemStatus getStatus() { return status; }
  public RentalItemStatus getTransferOriginStatus() { return transferOriginStatus; }
  public UUID getRentalTypeId() { return rentalTypeId; }
  public UUID getDimensionId() { return dimensionId; }
  public UUID getFinishingId() { return finishingId; }
  public UUID getCategoryId() { return categoryId; }
  public String getCategory() { return category; }
  public Boolean getLinoleum() { return linoleum; }
  public String getGeneralComment() { return generalComment; }
  public String getPassportJson() { return passportJson; }
  public String getTagsJson() { return tagsJson; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }

  @Override
  public final boolean equals(Object other) {
    if (this == other) return true;
    if (other == null) return false;
    Class<?> otherClass = other instanceof HibernateProxy proxy ? proxy.getHibernateLazyInitializer().getPersistentClass() : other.getClass();
    Class<?> thisClass = this instanceof HibernateProxy proxy ? proxy.getHibernateLazyInitializer().getPersistentClass() : getClass();
    return thisClass == otherClass && id != null && Objects.equals(id, ((RentalItem) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode() : getClass().hashCode();
  }
}
