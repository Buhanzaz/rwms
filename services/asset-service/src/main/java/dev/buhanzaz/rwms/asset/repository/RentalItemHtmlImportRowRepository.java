package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.RentalItemHtmlImportRow;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for service-local rental item html import row persistence.
 */
public interface RentalItemHtmlImportRowRepository
    extends JpaRepository<RentalItemHtmlImportRow, UUID> {
  Page<RentalItemHtmlImportRow> findAllByImportIdOrderBySourcePositionAscIdAsc(
      UUID importId, Pageable pageable);

  List<RentalItemHtmlImportRow> findAllByImportIdOrderBySourcePositionAscIdAsc(UUID importId);

  Optional<RentalItemHtmlImportRow> findByImportIdAndSourceRowId(UUID importId, String sourceRowId);

  List<RentalItemHtmlImportRow> findAllByImportIdAndSourceRowIdIn(
      UUID importId, Collection<String> sourceRowIds);
}
