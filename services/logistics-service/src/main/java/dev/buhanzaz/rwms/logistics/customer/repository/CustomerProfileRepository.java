package dev.buhanzaz.rwms.logistics.customer.repository;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerProfile;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persists the one-to-one auth-subject to rental-client customer binding. */
public interface CustomerProfileRepository extends JpaRepository<CustomerProfile, UUID> {
  Optional<CustomerProfile> findByAuthSubjectId(UUID authSubjectId);

  Optional<CustomerProfile> findByClientId(UUID clientId);
}
