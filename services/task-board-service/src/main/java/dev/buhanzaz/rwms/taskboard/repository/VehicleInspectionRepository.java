package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.VehicleInspection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence boundary for one pre-trip inspection per shift. */
public interface VehicleInspectionRepository extends JpaRepository<VehicleInspection, UUID> {
  Optional<VehicleInspection> findByShiftId(UUID shiftId);
}
