package dev.buhanzaz.rwms.logistics.customer.repository;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerNotification;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persists deduplicated, subject-owned customer inbox entries. */
public interface CustomerNotificationRepository extends JpaRepository<CustomerNotification, UUID> {
  boolean existsByCustomerSubjectIdAndOrderIdAndKind(
      UUID customerSubjectId, UUID orderId, String kind);
}
