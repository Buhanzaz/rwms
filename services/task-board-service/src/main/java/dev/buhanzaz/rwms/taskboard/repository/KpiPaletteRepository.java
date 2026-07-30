package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.KpiPalette;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KpiPaletteRepository extends JpaRepository<KpiPalette, UUID> {}
