package dev.buhanzaz.rwms.asset.service;

import dev.buhanzaz.rwms.asset.api.CabinStatusColorsResponse;
import dev.buhanzaz.rwms.asset.api.ReplaceCabinStatusColorsRequest;
import dev.buhanzaz.rwms.asset.domain.CabinStatusColors;
import dev.buhanzaz.rwms.asset.mapper.CabinStatusColorsMapper;
import dev.buhanzaz.rwms.asset.repository.CabinStatusColorsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns global cabin presentation settings without changing any aggregate status or event. */
@Service
public class CabinStatusColorsService {
  private final CabinStatusColorsRepository repository;
  private final CabinStatusColorsMapper mapper;

  public CabinStatusColorsService(
      CabinStatusColorsRepository repository, CabinStatusColorsMapper mapper) {
    this.repository = repository;
    this.mapper = mapper;
  }

  @Transactional(readOnly = true)
  public CabinStatusColorsResponse get() {
    return mapper.toResponse(requireSettings());
  }

  /** Manual expected-version check plus JPA optimistic locking protect concurrent replacement. */
  @Transactional
  public CabinStatusColorsResponse replace(ReplaceCabinStatusColorsRequest request) {
    CabinStatusColors settings = requireSettings();
    if (request.expectedVersion() == null || settings.getVersion() != request.expectedVersion()) {
      throw new AssetConflictException("Cabin status colors changed; reload the global palette");
    }
    if (settings.replace(request.colors())) repository.flush();
    return mapper.toResponse(settings);
  }

  private CabinStatusColors requireSettings() {
    return repository
        .findById(CabinStatusColors.SINGLETON_ID)
        .orElseThrow(() -> new IllegalStateException("Global cabin status colors are missing"));
  }
}
