package dev.buhanzaz.rwms.logistics.retention.repository;

import dev.buhanzaz.rwms.logistics.retention.domain.LogisticsArchiveManifest;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persists immutable private-archive checksums and their independent verification. */
public interface LogisticsArchiveManifestRepository
    extends JpaRepository<LogisticsArchiveManifest, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select manifest from LogisticsArchiveManifest manifest where manifest.id = :id")
  Optional<LogisticsArchiveManifest> findForUpdate(@Param("id") UUID id);
}
