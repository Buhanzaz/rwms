package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.RentalItemFactProjection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RentalItemFactProjectionRepository
    extends JpaRepository<RentalItemFactProjection, UUID> {}
