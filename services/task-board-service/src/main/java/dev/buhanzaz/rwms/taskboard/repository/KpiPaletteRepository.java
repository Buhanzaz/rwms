package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.KpiPalette;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence boundary for the current installation-wide KPI palette and its ranges. */
public interface KpiPaletteRepository extends JpaRepository<KpiPalette, UUID> {}
