package dev.buhanzaz.rwms.asset.disposition;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PropertyDispositionEffectRepository
    extends JpaRepository<PropertyDispositionEffect, UUID> {
  Optional<PropertyDispositionEffect> findByDecisionId(UUID decisionId);
}
