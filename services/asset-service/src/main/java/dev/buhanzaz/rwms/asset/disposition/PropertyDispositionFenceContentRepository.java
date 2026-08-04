package dev.buhanzaz.rwms.asset.disposition;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PropertyDispositionFenceContentRepository
    extends JpaRepository<PropertyDispositionFenceContent, UUID> {
  List<PropertyDispositionFenceContent> findAllByDecisionIdOrderByEquipmentIdAsc(UUID decisionId);
}
