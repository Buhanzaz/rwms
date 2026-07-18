package dev.buhanzaz.rwms.logistics.repository;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LogisticsDocumentRepository extends JpaRepository<LogisticsDocument, UUID> {
  Optional<LogisticsDocument> findByIdAndDocumentType(UUID id, LogisticsDocumentType documentType);

  List<LogisticsDocument> findAllByDocumentTypeAndWarehouseIdOrderByCreatedAtDescIdDesc(
      LogisticsDocumentType documentType, UUID warehouseId);
}
