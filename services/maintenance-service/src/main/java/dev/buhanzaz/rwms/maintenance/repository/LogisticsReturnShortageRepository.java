package dev.buhanzaz.rwms.maintenance.repository;

import dev.buhanzaz.rwms.maintenance.domain.LogisticsReturnShortage;
import dev.buhanzaz.rwms.maintenance.domain.LogisticsReturnShortageId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LogisticsReturnShortageRepository
    extends JpaRepository<LogisticsReturnShortage, LogisticsReturnShortageId> {}
