package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.DriverShift;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Optimistically fenced daily Driver Shift aggregate repository. */
public interface DriverShiftRepository extends JpaRepository<DriverShift, UUID> {
  Optional<DriverShift> findByDriverIdAndWorkDate(UUID driverId, LocalDate workDate);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select shift from DriverShift shift where shift.id=:id")
  Optional<DriverShift> findByIdForUpdate(@Param("id") UUID id);
}
