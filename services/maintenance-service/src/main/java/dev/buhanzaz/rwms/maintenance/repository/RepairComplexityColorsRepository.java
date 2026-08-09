package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.RepairComplexityColors;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data persistence boundary for RepairComplexityColors; business transitions remain in the owning service. */
public interface RepairComplexityColorsRepository
    extends JpaRepository<RepairComplexityColors, UUID> {}
