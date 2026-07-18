package dev.buhanzaz.rwms.logistics.repository;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LogisticsDocumentLineRepository extends JpaRepository<LogisticsDocumentLine, UUID> {
  List<LogisticsDocumentLine> findAllByDocument_IdOrderByLineNumber(UUID documentId);
}
