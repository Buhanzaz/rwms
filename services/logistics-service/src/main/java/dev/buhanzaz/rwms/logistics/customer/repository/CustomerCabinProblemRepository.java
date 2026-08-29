package dev.buhanzaz.rwms.logistics.customer.repository;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerCabinProblem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persists immutable customer problem reports and their idempotency receipts. */
public interface CustomerCabinProblemRepository extends JpaRepository<CustomerCabinProblem, UUID> {
  Optional<CustomerCabinProblem> findByCustomerSubjectIdAndIdempotencyKey(
      UUID customerSubjectId, UUID idempotencyKey);

  List<CustomerCabinProblem> findAllByBookingIdOrderByReportedAtAscIdAsc(UUID bookingId);
}
