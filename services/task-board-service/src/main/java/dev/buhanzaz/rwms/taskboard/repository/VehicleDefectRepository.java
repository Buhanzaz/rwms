package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.DefectSeverity;
import dev.buhanzaz.rwms.taskboard.domain.DefectStatus;
import dev.buhanzaz.rwms.taskboard.domain.VehicleDefect;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Shared persistence boundary for pre-trip and end-of-shift vehicle defects. */
public interface VehicleDefectRepository extends JpaRepository<VehicleDefect, UUID> {
  List<VehicleDefect> findAllByShiftIdOrderByCreatedAtAsc(UUID shiftId);

  Optional<VehicleDefect> findByIdAndShiftId(UUID id, UUID shiftId);

  long countByShiftIdAndSeverityAndStatus(
      UUID shiftId, DefectSeverity severity, DefectStatus status);
}
