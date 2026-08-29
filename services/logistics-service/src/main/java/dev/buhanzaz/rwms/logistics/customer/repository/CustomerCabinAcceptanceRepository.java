package dev.buhanzaz.rwms.logistics.customer.repository;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerCabinAcceptance;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persists immutable customer acceptance facts and their idempotency receipts. */
public interface CustomerCabinAcceptanceRepository
    extends JpaRepository<CustomerCabinAcceptance, UUID> {
  Optional<CustomerCabinAcceptance> findByCustomerSubjectIdAndIdempotencyKey(
      UUID customerSubjectId, UUID idempotencyKey);

  Optional<CustomerCabinAcceptance> findByBookingIdAndCabinUnitId(
      UUID bookingId, UUID cabinUnitId);

  List<CustomerCabinAcceptance> findAllByBookingIdOrderByAcceptedAtAscIdAsc(UUID bookingId);
}
