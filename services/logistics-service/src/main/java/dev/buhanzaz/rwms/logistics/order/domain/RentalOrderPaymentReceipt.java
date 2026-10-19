package dev.buhanzaz.rwms.logistics.order.domain;

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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.proxy.HibernateProxy;

/** Append-only initial bill: tariff and composition edits cannot rewrite an issued receipt. */
@Entity
@Table(name = "rental_order_payment_receipt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RentalOrderPaymentReceipt {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "order_id",
      nullable = false,
      unique = true,
      updatable = false,
      foreignKey = @ForeignKey(name = "fk_rental_order_payment_receipt_order"))
  private RentalOrder order;

  @NotNull
  @Column(name = "issued_at", nullable = false, updatable = false)
  private OffsetDateTime issuedAt;

  @NotBlank
  @Column(name = "receipt_json", nullable = false, updatable = false, columnDefinition = "text")
  private String receiptJson;

  public static RentalOrderPaymentReceipt issue(
      RentalOrder order, OffsetDateTime issuedAt, String receiptJson) {
    RentalOrderPaymentReceipt receipt = new RentalOrderPaymentReceipt();
    receipt.order = Objects.requireNonNull(order, "order");
    receipt.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
    if (receiptJson == null || receiptJson.isBlank()) {
      throw new IllegalArgumentException("Receipt data is required");
    }
    receipt.receiptJson = receiptJson;
    return receipt;
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
        && getId() != null
        && Objects.equals(getId(), ((RentalOrderPaymentReceipt) other).getId());
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
