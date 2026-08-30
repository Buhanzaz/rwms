package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.DriverShiftPhoto;
import dev.buhanzaz.rwms.taskboard.domain.ShiftPhotoRole;
import dev.buhanzaz.rwms.taskboard.domain.ShiftPhotoState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence boundary for shift-owned media reservations. */
public interface DriverShiftPhotoRepository extends JpaRepository<DriverShiftPhoto, UUID> {
  List<DriverShiftPhoto> findAllByShiftIdOrderByRecordedAtAsc(UUID shiftId);

  Optional<DriverShiftPhoto> findByEvidenceId(UUID evidenceId);

  Optional<DriverShiftPhoto> findByShiftIdAndClientReferenceId(
      UUID shiftId, UUID clientReferenceId);

  long countByDefectIdAndRoleAndState(
      UUID defectId, ShiftPhotoRole role, ShiftPhotoState state);
}
