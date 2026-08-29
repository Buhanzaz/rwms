package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Warehouse-scoped operational worker profile and credential-workflow projection.
 *
 * <p>Authentication secrets and tokens remain auth-service-owned; this entity retains only the
 * task-board state required for assignment and recoverable credential operations.
 */
@Entity
@Table(
    name = "worker",
    uniqueConstraints =
        @UniqueConstraint(name = "uk_worker_app_login", columnNames = "app_login"),
    indexes =
        @Index(
            name = "idx_worker_warehouse",
            columnList = "warehouse_id,active,display_name"))
public class Worker extends AbstractVersionedEntity {
  @Column(name = "revision_marker", nullable = false)
  private UUID revisionMarker = UUID.randomUUID();

  @NotNull
  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "first_name", length = 128)
  private String firstName;

  @Column(name = "last_name", length = 128)
  private String lastName;

  @Column(name = "middle_name", length = 128)
  private String middleName;

  @NotBlank
  @Column(name = "display_name", nullable = false, length = 256)
  private String displayName;

  @Column(name = "active", nullable = false)
  private boolean active = true;

  @Column(name = "comment_text", length = 1000)
  private String comment;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "employment_type", nullable = false, length = 16)
  private WorkerEmploymentType employmentType = WorkerEmploymentType.STAFF;

  @Column(name = "phone", length = 64)
  private String phone;

  @Column(name = "contract_available_from")
  private OffsetDateTime contractAvailableFrom;

  @Column(name = "contract_available_until")
  private OffsetDateTime contractAvailableUntil;

  @Column(name = "app_login", length = 128)
  private String appLogin;

  @Enumerated(EnumType.STRING)
  @Column(name = "credential_status", nullable = false, length = 32)
  private CredentialStatus credentialStatus = CredentialStatus.NOT_CONFIGURED;

  @Column(name = "credential_error", length = 1000)
  private String credentialError;

  @Column(name = "credential_operation_id")
  private UUID credentialOperationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "credential_operation_type", length = 32)
  private CredentialOperationType credentialOperationType;

  @Column(name = "credential_operation_started_at")
  private OffsetDateTime credentialOperationStartedAt;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "current_group_id",
      foreignKey = @ForeignKey(name = "fk_worker_current_group"))
  private WorkerGroup currentGroup;

  @PrePersist
  @PreUpdate
  void normalize() {
    firstName = trim(firstName);
    lastName = trim(lastName);
    middleName = trim(middleName);
    phone = trim(phone);
    String normalizedLogin = trim(appLogin);
    appLogin = normalizedLogin == null ? null : normalizedLogin.toLowerCase(Locale.ROOT);
    List<String> parts = new ArrayList<>();
    if (lastName != null) parts.add(lastName);
    if (firstName != null) parts.add(firstName);
    if (middleName != null) parts.add(middleName);
    displayName = trim(displayName);
    if (displayName == null && !parts.isEmpty()) displayName = String.join(" ", parts);
    validateEmployment();
  }

  private String trim(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public void setWarehouseId(UUID warehouseId) {
    this.warehouseId = warehouseId;
  }

  public String getFirstName() {
    return firstName;
  }

  public void setFirstName(String firstName) {
    this.firstName = firstName;
  }

  public String getLastName() {
    return lastName;
  }

  public void setLastName(String lastName) {
    this.lastName = lastName;
  }

  public String getMiddleName() {
    return middleName;
  }

  public void setMiddleName(String middleName) {
    this.middleName = middleName;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public String getComment() {
    return comment;
  }

  public void setComment(String comment) {
    this.comment = comment;
  }

  /** Configures this profile as a contractor available only inside the supplied time range. */
  public void configureContractor(
      String phone, OffsetDateTime availableFrom, OffsetDateTime availableUntil) {
    String normalizedPhone = trim(phone);
    if (normalizedPhone == null) {
      throw new IllegalArgumentException("Укажите телефон наёмного водителя");
    }
    if (availableFrom == null
        || availableUntil == null
        || !availableUntil.isAfter(availableFrom)) {
      throw new IllegalArgumentException(
          "Период доступности наёмного водителя задан некорректно");
    }
    employmentType = WorkerEmploymentType.CONTRACTOR;
    this.phone = normalizedPhone;
    contractAvailableFrom = availableFrom;
    contractAvailableUntil = availableUntil;
  }

  /** Returns whether a contractor contract covers the supplied instant; staff are always covered. */
  public boolean contractCovers(OffsetDateTime at) {
    if (employmentType == WorkerEmploymentType.STAFF) {
      return true;
    }
    return at != null
        && !at.isBefore(contractAvailableFrom)
        && at.isBefore(contractAvailableUntil);
  }

  public WorkerEmploymentType getEmploymentType() {
    return employmentType;
  }

  public String getPhone() {
    return phone;
  }

  public OffsetDateTime getContractAvailableFrom() {
    return contractAvailableFrom;
  }

  public OffsetDateTime getContractAvailableUntil() {
    return contractAvailableUntil;
  }

  public String getAppLogin() {
    return appLogin;
  }

  public void setAppLogin(String appLogin) {
    this.appLogin = appLogin;
  }

  public CredentialStatus getCredentialStatus() {
    return credentialStatus;
  }

  public void setCredentialStatus(CredentialStatus credentialStatus) {
    this.credentialStatus = credentialStatus;
  }

  public String getCredentialError() {
    return credentialError;
  }

  public void setCredentialError(String credentialError) {
    this.credentialError = credentialError;
  }

  public UUID getCredentialOperationId() {
    return credentialOperationId;
  }

  public void setCredentialOperationId(UUID credentialOperationId) {
    this.credentialOperationId = credentialOperationId;
  }

  public CredentialOperationType getCredentialOperationType() {
    return credentialOperationType;
  }

  public void setCredentialOperationType(CredentialOperationType credentialOperationType) {
    this.credentialOperationType = credentialOperationType;
  }

  public OffsetDateTime getCredentialOperationStartedAt() {
    return credentialOperationStartedAt;
  }

  public void setCredentialOperationStartedAt(OffsetDateTime credentialOperationStartedAt) {
    this.credentialOperationStartedAt = credentialOperationStartedAt;
  }

  public WorkerGroup getCurrentGroup() {
    return currentGroup;
  }

  public void setCurrentGroup(WorkerGroup currentGroup) {
    this.currentGroup = currentGroup;
  }

  public void touch() {
    revisionMarker = UUID.randomUUID();
  }

  public UUID getRevisionMarker() {
    return revisionMarker;
  }

  private void validateEmployment() {
    if (employmentType == WorkerEmploymentType.STAFF) {
      if (phone != null || contractAvailableFrom != null || contractAvailableUntil != null) {
        throw new IllegalArgumentException(
            "Период подрядчика допустим только для наёмного водителя");
      }
      return;
    }
    if (employmentType != WorkerEmploymentType.CONTRACTOR
        || phone == null
        || contractAvailableFrom == null
        || contractAvailableUntil == null
        || !contractAvailableUntil.isAfter(contractAvailableFrom)) {
      throw new IllegalArgumentException("Профиль наёмного водителя заполнен не полностью");
    }
  }
}
