package dev.buhanzaz.rwms.logistics.customer.claims;

import dev.buhanzaz.rwms.logistics.customer.claims.domain.CustomerCabinProblemAction;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Reads the immutable lifecycle history of customer cabin problems. */
public interface CustomerCabinProblemActionRepository
    extends JpaRepository<CustomerCabinProblemAction, UUID> {
  List<CustomerCabinProblemAction> findAllByProblemIdOrderByOccurredAtDescIdDesc(UUID problemId);
}
