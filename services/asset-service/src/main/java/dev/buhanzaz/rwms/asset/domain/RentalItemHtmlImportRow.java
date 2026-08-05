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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.proxy.HibernateProxy;

@Entity
@Table(name = "rental_item_html_import_row")
public class RentalItemHtmlImportRow {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "import_id", nullable = false)
  private UUID importId;

  @Column(name = "source_row_id", nullable = false, length = 64)
  private String sourceRowId;

  @Column(name = "source_position", nullable = false)
  private int sourcePosition;

  @Column(name = "source_number", length = 512)
  private String sourceNumber;

  @Column(name = "proposed_number", length = 128)
  private String proposedNumber;

  @Column(name = "identity_match_key", length = 128)
  private String identityMatchKey;

  @Column(name = "target_rental_item_id")
  private UUID targetRentalItemId;

  @Enumerated(EnumType.STRING)
  @Column(name = "action", nullable = false, length = 16)
  private RentalItemHtmlImportRowAction action;

  @Column(name = "has_photo_link", nullable = false)
  private boolean hasPhotoLink;

  @Column(name = "parsed_json", nullable = false, length = 32000)
  private String parsedJson;

  @Column(name = "decision_json", nullable = false, length = 16000)
  private String decisionJson = "{}";

  @Column(name = "diagnostic_codes_json", nullable = false, length = 8000)
  private String diagnosticCodesJson = "[]";

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected RentalItemHtmlImportRow() {}

  public static RentalItemHtmlImportRow create(
      UUID importId,
      String sourceRowId,
      int sourcePosition,
      String sourceNumber,
      String proposedNumber,
      String identityMatchKey,
      UUID targetRentalItemId,
      RentalItemHtmlImportRowAction action,
      boolean hasPhotoLink,
      String parsedJson,
      String diagnosticCodesJson) {
    if (importId == null || sourceRowId == null || sourceRowId.isBlank()) {
      throw new IllegalArgumentException("HTML import row identity is required");
    }
    if (sourceRowId.length() > 64 || sourcePosition < 0 || action == null) {
      throw new IllegalArgumentException("HTML import row metadata is invalid");
    }
    RentalItemHtmlImportRow value = new RentalItemHtmlImportRow();
    value.importId = importId;
    value.sourceRowId = sourceRowId;
    value.sourcePosition = sourcePosition;
    value.sourceNumber = optional(sourceNumber, 512);
    value.proposedNumber = optional(proposedNumber, 128);
    value.identityMatchKey = optional(identityMatchKey, 128);
    value.targetRentalItemId = targetRentalItemId;
    value.action = action;
    value.hasPhotoLink = hasPhotoLink;
    value.parsedJson = jsonObject(parsedJson, 32000);
    value.diagnosticCodesJson = jsonArray(diagnosticCodesJson, 8000);
    return value;
  }

  public void decide(
      RentalItemHtmlImportRowAction action,
      String proposedNumber,
      UUID targetRentalItemId,
      String decisionJson) {
    if (action == null) throw new IllegalArgumentException("HTML import row action is required");
    this.action = action;
    this.proposedNumber = optional(proposedNumber, 128);
    this.targetRentalItemId = targetRentalItemId;
    this.decisionJson = jsonObject(decisionJson, 16000);
  }

  public void removePrivatePhotoKey(String sanitizedParsedJson) {
    if (hasPhotoLink) {
      this.parsedJson = jsonObject(sanitizedParsedJson, 32000);
    }
  }

  /**
   * A created cabin is bound only after the local asset transaction succeeds.
   * The source action deliberately remains CREATE: binding it as MERGE would
   * falsely imply that an existing cabin was mutated.
   */
  public void bindCreatedRentalItem(UUID rentalItemId) {
    if (rentalItemId == null || action != RentalItemHtmlImportRowAction.CREATE) {
      throw new IllegalStateException("Only a created HTML import row can bind a cabin");
    }
    if (targetRentalItemId != null && !targetRentalItemId.equals(rentalItemId)) {
      throw new IllegalStateException("HTML import row is already bound to another cabin");
    }
    targetRentalItemId = rentalItemId;
  }

  @PrePersist
  void prePersist() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static String optional(String value, int maximum) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.trim();
    if (normalized.length() > maximum) throw new IllegalArgumentException("Value is too long");
    return normalized;
  }

  private static String jsonObject(String value, int maximum) {
    String normalized = value == null || value.isBlank() ? "{}" : value.trim();
    if (normalized.length() > maximum
        || !normalized.startsWith("{")
        || !normalized.endsWith("}")) {
      throw new IllegalArgumentException("HTML import row JSON must be a bounded object");
    }
    return normalized;
  }

  private static String jsonArray(String value, int maximum) {
    String normalized = value == null || value.isBlank() ? "[]" : value.trim();
    if (normalized.length() > maximum
        || !normalized.startsWith("[")
        || !normalized.endsWith("]")) {
      throw new IllegalArgumentException("HTML import diagnostics must be a bounded array");
    }
    return normalized;
  }

  public UUID getId() {
    return id;
  }

  public UUID getImportId() {
    return importId;
  }

  public String getSourceRowId() {
    return sourceRowId;
  }

  public int getSourcePosition() {
    return sourcePosition;
  }

  public String getSourceNumber() {
    return sourceNumber;
  }

  public String getProposedNumber() {
    return proposedNumber;
  }

  public String getIdentityMatchKey() {
    return identityMatchKey;
  }

  public UUID getTargetRentalItemId() {
    return targetRentalItemId;
  }

  public RentalItemHtmlImportRowAction getAction() {
    return action;
  }

  public boolean hasPhotoLink() {
    return hasPhotoLink;
  }

  public String getParsedJson() {
    return parsedJson;
  }

  public String getDecisionJson() {
    return decisionJson;
  }

  public String getDiagnosticCodesJson() {
    return diagnosticCodesJson;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
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
        && Objects.equals(id, ((RentalItemHtmlImportRow) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
