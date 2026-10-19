package dev.buhanzaz.rwms.logistics.customer.repository;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerBookingChangeAlertAcknowledgement;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persisted per-manager read receipts, not a state transition of the customer booking. */
public interface CustomerBookingChangeAlertAcknowledgementRepository
    extends JpaRepository<CustomerBookingChangeAlertAcknowledgement, UUID> {
  Optional<CustomerBookingChangeAlertAcknowledgement> findByManagerIdAndIdempotencyKey(
      UUID managerId, UUID key);

  Optional<CustomerBookingChangeAlertAcknowledgement> findByManagerIdAndMutationId(
      UUID managerId, UUID mutationId);
}
