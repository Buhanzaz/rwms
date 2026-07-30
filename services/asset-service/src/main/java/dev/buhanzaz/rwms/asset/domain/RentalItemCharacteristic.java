package dev.buhanzaz.rwms.asset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.proxy.HibernateProxy;

/** One selected catalog characteristic of a cabin; never a comma-delimited text value. */
@Entity
@Table(name = "rental_item_characteristic")
public class RentalItemCharacteristic {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "rental_item_id", nullable = false)
  private UUID rentalItemId;

  @Column(name = "characteristic_id", nullable = false)
  private UUID characteristicId;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  protected RentalItemCharacteristic() {}

  public static RentalItemCharacteristic create(
      UUID rentalItemId, UUID characteristicId, int sortOrder) {
    if (rentalItemId == null || characteristicId == null || sortOrder < 0) {
      throw new IllegalArgumentException("Rental-item characteristic is incomplete");
    }
    RentalItemCharacteristic item = new RentalItemCharacteristic();
    item.rentalItemId = rentalItemId;
    item.characteristicId = characteristicId;
    item.sortOrder = sortOrder;
    return item;
  }

  public UUID getId() {
    return id;
  }

  public UUID getRentalItemId() {
    return rentalItemId;
  }

  public UUID getCharacteristicId() {
    return characteristicId;
  }

  public int getSortOrder() {
    return sortOrder;
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
        && Objects.equals(id, ((RentalItemCharacteristic) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
