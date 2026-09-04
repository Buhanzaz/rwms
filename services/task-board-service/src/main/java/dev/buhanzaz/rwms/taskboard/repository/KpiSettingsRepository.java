package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.KpiSettings;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence boundary for the installation-wide KPI configuration singleton. */
public interface KpiSettingsRepository extends JpaRepository<KpiSettings, UUID> {}
