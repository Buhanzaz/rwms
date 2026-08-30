package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.VehicleInspectionItemResult;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Ordered and independently locking persistence for resumable inspection item results. */
public interface VehicleInspectionItemResultRepository
    extends JpaRepository<VehicleInspectionItemResult, UUID> {
  List<VehicleInspectionItemResult> findAllByInspectionIdOrderBySortOrderAsc(UUID inspectionId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select item from VehicleInspectionItemResult item where item.id=:id")
  Optional<VehicleInspectionItemResult> findByIdForUpdate(@Param("id") UUID id);
}
