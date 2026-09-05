package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Objects;
import java.util.UUID;

/** City-owned contractor contact catalog; trip dates and assignments remain driver-owned. */
@Entity
@Table(
    name = "contractor_company",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_contractor_company_inn",
          columnNames = {"warehouse_id", "inn"}),
      @UniqueConstraint(
          name = "uk_contractor_company_owner",
          columnNames = {"id", "warehouse_id"})
    })
public class ContractorCompany extends AbstractVersionedEntity {
  @NotNull
  @Column(name = "warehouse_id", nullable = false, updatable = false)
  private UUID warehouseId;

  @NotBlank
  @Size(max = 256)
  @Column(name = "name", nullable = false, length = 256)
  private String name;

  @NotBlank
  @Pattern(regexp = "[0-9]{10}|[0-9]{12}")
  @Column(name = "inn", nullable = false, length = 12)
  private String inn;

  @Size(max = 256)
  @Column(name = "contact_name", length = 256)
  private String contactName;

  @NotBlank
  @Size(max = 64)
  @Column(name = "phone", nullable = false, length = 64)
  private String phone;

  @Email
  @Size(max = 256)
  @Column(name = "email", length = 256)
  private String email;

  @Size(max = 1000)
  @Column(name = "address", length = 1000)
  private String address;

  @Size(max = 2000)
  @Column(name = "comment_text", length = 2000)
  private String comment;

  protected ContractorCompany() {}

  /** Establishes immutable identity and city ownership for one caller-stable create command. */
  public ContractorCompany(UUID id, UUID warehouseId) {
    assignReviewedId(id);
    this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
  }

  /** Replaces contact details without moving the company or its drivers between cities. */
  public void replaceDetails(
      String name,
      String inn,
      String contactName,
      String phone,
      String email,
      String address,
      String comment) {
    this.name = normalized(name);
    this.inn = normalized(inn);
    this.contactName = normalized(contactName);
    this.phone = normalized(phone);
    this.email = normalized(email);
    this.address = normalized(address);
    this.comment = normalized(comment);
    if (this.name == null || this.phone == null) {
      throw new IllegalArgumentException("Укажите название компании и контактный телефон");
    }
    if (this.inn == null || !this.inn.matches("[0-9]{10}|[0-9]{12}")) {
      throw new IllegalArgumentException("ИНН должен содержать 10 или 12 цифр");
    }
  }

  /** Stable-ID replays may return only the same normalized company profile. */
  public boolean sameDetails(ContractorCompany other) {
    return warehouseId.equals(other.warehouseId)
        && Objects.equals(name, other.name)
        && Objects.equals(inn, other.inn)
        && Objects.equals(contactName, other.contactName)
        && Objects.equals(phone, other.phone)
        && Objects.equals(email, other.email)
        && Objects.equals(address, other.address)
        && Objects.equals(comment, other.comment);
  }

  private static String normalized(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public String getName() {
    return name;
  }

  public String getInn() {
    return inn;
  }

  public String getContactName() {
    return contactName;
  }

  public String getPhone() {
    return phone;
  }

  public String getEmail() {
    return email;
  }

  public String getAddress() {
    return address;
  }

  public String getComment() {
    return comment;
  }
}
