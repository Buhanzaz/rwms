package dev.buhanzaz.rwms.analytics.repository;

import dev.buhanzaz.rwms.analytics.domain.AnalyticsInbox;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for the transactional event inbox keyed by canonical event identity. */
public interface AnalyticsInboxRepository extends JpaRepository<AnalyticsInbox, UUID> {}
