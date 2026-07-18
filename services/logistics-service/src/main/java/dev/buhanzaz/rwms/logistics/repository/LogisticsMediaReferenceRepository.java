package dev.buhanzaz.rwms.logistics.repository;

import dev.buhanzaz.rwms.logistics.domain.LogisticsMediaPurpose;
import dev.buhanzaz.rwms.logistics.domain.LogisticsMediaReference;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LogisticsMediaReferenceRepository
    extends JpaRepository<LogisticsMediaReference, UUID> {
  List<LogisticsMediaReference> findAllByDocument_IdAndPurposeOrderByCreatedAtAsc(
      UUID documentId, LogisticsMediaPurpose purpose);

  List<LogisticsMediaReference> findAllByLine_IdAndPurposeOrderByCreatedAtAsc(
      UUID lineId, LogisticsMediaPurpose purpose);
}
