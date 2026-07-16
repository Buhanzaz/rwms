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

@Entity
@Table(name = "rental_item")
public class RentalItem {
  private static final Pattern NUMBER = Pattern.compile("^[\\p{L}\\p{N}]{1,128}$");

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "number", nullable = false, length = 128)
  private String number;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 64)
  private RentalItemStatus status;

  @Column(name = "rental_type", length = 255)
  private String rentalType;

  @Column(name = "dimensions", length = 255)
  private String dimensions;

  @Column(name = "finishing", length = 255)
  private String finishing;

  @Column(name = "category", length = 255)
  private String category;

  @Column(name = "characteristics", length = 2000)
  private String characteristics;

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
      String rentalType,
      String dimensions,
      String finishing,
      String category,
      String characteristics,
      Boolean linoleum,
      String passportJson,
      String tagsJson) {
    if (warehouseId == null) throw new IllegalArgumentException("warehouseId is required");
    RentalItem item = new RentalItem();
    item.warehouseId = warehouseId;
    item.number = canonicalNumber(number);
    item.status = RentalItemStatus.NEW;
    item.assignPassport(
        rentalType, dimensions, finishing, category, characteristics, linoleum, passportJson, tagsJson);
    return item;
  }

  public boolean changePassport(
      String rentalType,
      String dimensions,
      String finishing,
      String category,
      String characteristics,
      Boolean linoleum,
      String passportJson,
      String tagsJson) {
    String nextType = optional(rentalType, 255);
    String nextDimensions = optional(dimensions, 255);
    String nextFinishing = optional(finishing, 255);
    String nextCategory = optional(category, 255);
    String nextCharacteristics = optional(characteristics, 2000);
    String nextPassport = jsonObject(passportJson);
    String nextTags = jsonArray(tagsJson);
    if (Objects.equals(this.rentalType, nextType)
        && Objects.equals(this.dimensions, nextDimensions)
        && Objects.equals(this.finishing, nextFinishing)
        && Objects.equals(this.category, nextCategory)
        && Objects.equals(this.characteristics, nextCharacteristics)
        && Objects.equals(this.linoleum, linoleum)
        && Objects.equals(this.passportJson, nextPassport)
        && Objects.equals(this.tagsJson, nextTags)) return false;
    this.rentalType = nextType;
    this.dimensions = nextDimensions;
    this.finishing = nextFinishing;
    this.category = nextCategory;
    this.characteristics = nextCharacteristics;
    this.linoleum = linoleum;
    this.passportJson = nextPassport;
    this.tagsJson = nextTags;
    return true;
  }

  public boolean changeStatus(RentalItemStatus next) {
    if (next == null) throw new IllegalArgumentException("status is required");
    if (status == next) return false;
    if (!status.acceptsManualStatusChangeTo(next)) {
      throw new IllegalStateException("Rental-item status is fenced or terminal");
    }
    status = next;
    return true;
  }

  /**
   * A service-to-service operation that holds the current operation lease may
   * perform a fenced status transition, including the otherwise manual-fenced
   * write-off state. The caller must validate the lease before invoking this
   * transition.
   */
  public boolean changeStatusUnderLease(RentalItemStatus next) {
    if (next == null) throw new IllegalArgumentException("status is required");
    if (status == next) return false;
    status = next;
    return true;
  }

  public boolean changeWarehouse(UUID nextWarehouseId) {
    if (nextWarehouseId == null) throw new IllegalArgumentException("warehouseId is required");
    if (warehouseId.equals(nextWarehouseId)) return false;
    if (status == RentalItemStatus.WRITTEN_OFF || status == RentalItemStatus.IN_TRANSFER) {
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
      String rentalType,
      String dimensions,
      String finishing,
      String category,
      String characteristics,
      Boolean linoleum,
      String passportJson,
      String tagsJson) {
    this.rentalType = optional(rentalType, 255);
    this.dimensions = optional(dimensions, 255);
    this.finishing = optional(finishing, 255);
    this.category = optional(category, 255);
    this.characteristics = optional(characteristics, 2000);
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
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    number = canonicalNumber(number);
  }

  public static String canonicalNumber(String value) {
    String candidate = value == null ? "" : value.trim().replaceAll("[^\\p{L}\\p{N}]+", "");
    String normalized = candidate.toUpperCase(Locale.ROOT);
    if (!NUMBER.matcher(normalized).matches()) {
      throw new IllegalArgumentException("Rental item number must contain 1 to 128 letters or digits");
    }
    return normalized;
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
  public RentalItemStatus getStatus() { return status; }
  public String getRentalType() { return rentalType; }
  public String getDimensions() { return dimensions; }
  public String getFinishing() { return finishing; }
  public String getCategory() { return category; }
  public String getCharacteristics() { return characteristics; }
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
