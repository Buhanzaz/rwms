package dev.buhanzaz.rwms.logistics.contractor.share.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.proxy.HibernateProxy;

/**
 * Immutable member of one contractor route share, binding the public external identity to exact
 * logistics-owned task, document and optional rental-order identities.
 */
@Entity
@Table(
    name = "contractor_route_share_task",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_contractor_route_share_task_position",
          columnNames = {"share_id", "position"}),
      @UniqueConstraint(
          name = "uk_contractor_route_share_task_external",
          columnNames = {"share_id", "external_task_id"})
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContractorRouteShareTask {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "share_id", nullable = false)
  private ContractorRouteShare share;

  @Column(name = "position", nullable = false)
  private int position;

  @Column(name = "external_task_id", nullable = false)
  private UUID externalTaskId;

  @Column(name = "driver_task_id", nullable = false)
  private UUID driverTaskId;

  @Column(name = "document_id", nullable = false)
  private UUID documentId;

  @Column(name = "rental_order_id")
  private UUID rentalOrderId;

  static ContractorRouteShareTask create(
      ContractorRouteShare share, int position, ContractorRouteShare.TaskBinding binding) {
    if (position < 0) throw new IllegalArgumentException("Task position is invalid");
    ContractorRouteShareTask task = new ContractorRouteShareTask();
    task.share = Objects.requireNonNull(share, "share");
    task.position = position;
    task.externalTaskId = Objects.requireNonNull(binding.externalTaskId(), "externalTaskId");
    task.driverTaskId = Objects.requireNonNull(binding.driverTaskId(), "driverTaskId");
    task.documentId = Objects.requireNonNull(binding.documentId(), "documentId");
    task.rentalOrderId = binding.rentalOrderId();
    return task;
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
        && Objects.equals(id, ((ContractorRouteShareTask) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
