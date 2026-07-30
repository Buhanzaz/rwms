package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.RentalItemHtmlImport;
import dev.buhanzaz.rwms.asset.domain.RentalItemHtmlImportState;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RentalItemHtmlImportRepository
    extends JpaRepository<RentalItemHtmlImport, UUID> {
  Optional<RentalItemHtmlImport> findByActorSubjectIdAndIdempotencyKey(
      UUID actorSubjectId, UUID idempotencyKey);

  List<RentalItemHtmlImport> findAllByWarehouseIdOrderByUpdatedAtDescIdAsc(UUID warehouseId);

  List<RentalItemHtmlImport> findTop50ByStateInOrderByUpdatedAtAscIdAsc(
      Collection<RentalItemHtmlImportState> states);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select value from RentalItemHtmlImport value where value.id = :id")
  Optional<RentalItemHtmlImport> findByIdForUpdate(@Param("id") UUID id);
}
