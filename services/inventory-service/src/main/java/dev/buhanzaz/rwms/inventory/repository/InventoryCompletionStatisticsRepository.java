package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.InventoryCompletionStatistics;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryCompletionStatisticsRepository
    extends JpaRepository<InventoryCompletionStatistics, UUID> {}
