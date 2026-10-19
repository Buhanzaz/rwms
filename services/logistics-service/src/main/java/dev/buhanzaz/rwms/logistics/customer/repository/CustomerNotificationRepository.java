package dev.buhanzaz.rwms.logistics.customer.repository;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerNotification;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persists deduplicated, subject-owned customer inbox entries. */
public interface CustomerNotificationRepository extends JpaRepository<CustomerNotification, UUID> {
  boolean existsByCustomerSubjectIdAndOrderIdAndKind(
      UUID customerSubjectId, UUID orderId, String kind);

  /** Returns the next bounded unread inbox batch in acknowledgement order. */
  List<CustomerNotification> findByCustomerSubjectIdAndReadAtIsNullOrderByCreatedAtAscIdAsc(
      UUID customerSubjectId, org.springframework.data.domain.Pageable page);

  /** Locks one exact subject-owned entry without revealing entries owned by other customers. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select notification
      from CustomerNotification notification
      where notification.id = :notificationId
        and notification.customerSubjectId = :customerSubjectId
      """)
  Optional<CustomerNotification> findOwnedForUpdate(
      @Param("notificationId") UUID notificationId,
      @Param("customerSubjectId") UUID customerSubjectId);

  /** Uses PostgreSQL wall-clock time so acknowledgement order is consistent across replicas. */
  @Query(value = "select clock_timestamp()", nativeQuery = true)
  Instant currentDatabaseTimestamp();
}
