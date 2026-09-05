package dev.buhanzaz.rwms.logistics.pricing.service;

import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.pricing.domain.RentalPricingSettings;
import dev.buhanzaz.rwms.logistics.pricing.mapper.RentalPricingMapper;
import dev.buhanzaz.rwms.logistics.pricing.repository.RentalPricingSettingsRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Logistics-local tariff transactions. Callers authorize the actor and resolve the live asset
 * catalog before entering this store; no network call occurs while the tariff row is locked.
 */
@Service
@RequiredArgsConstructor
public class RentalPricingStore {
  private final RentalPricingSettingsRepository settings;
  private final RentalPricingMapper mapper;

  /** Loads owner version and element collection from the same database snapshot, without writes. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public RentalPricingSnapshot read() {
    return mapper.toSnapshot(
        settings
            .findById(RentalPricingSettings.SINGLETON_ID)
            .orElseThrow(RentalPricingStore::missingSettings));
  }

  @Transactional
  public RentalPricingSnapshot update(
      long expectedVersion,
      UUID rentalTypeId,
      UUID categoryId,
      long monthlyPriceRubles,
      UUID actorSubjectId) {
    RentalPricingSettings value =
        settings
            .findForUpdate(RentalPricingSettings.SINGLETON_ID)
            .orElseThrow(RentalPricingStore::missingSettings);
    if (value.getVersion() != expectedVersion) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "RENTAL_PRICING_VERSION_CONFLICT",
          "Цены аренды уже изменены другим пользователем");
    }
    value.setMonthlyPrice(
        rentalTypeId,
        categoryId,
        monthlyPriceRubles,
        actorSubjectId,
        OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
    settings.flush();
    return mapper.toSnapshot(value);
  }

  private static OrderProblemException missingSettings() {
    return new OrderProblemException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "RENTAL_PRICING_UNAVAILABLE",
        "Настройки цен аренды недоступны");
  }
}
