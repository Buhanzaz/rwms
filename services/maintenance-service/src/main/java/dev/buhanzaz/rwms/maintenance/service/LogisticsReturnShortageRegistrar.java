package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.domain.LogisticsReturnShortage;
import dev.buhanzaz.rwms.maintenance.repository.LogisticsReturnShortageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Uses the source primary key as the concurrent first-write arbiter. */
@Service
@RequiredArgsConstructor
public class LogisticsReturnShortageRegistrar {
  private final LogisticsReturnShortageRepository sources;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean register(LogisticsReturnShortage candidate) {
    if (sources.existsById(candidate.getId())) {
      return false;
    }
    sources.saveAndFlush(candidate);
    return true;
  }
}
