package dev.buhanzaz.rwms.logistics.inquiry.service;

import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.RentalSettingsResponse;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.UpdateRentalSettingsRequest;
import dev.buhanzaz.rwms.logistics.inquiry.domain.LateChangeFeeMode;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalSettings;
import dev.buhanzaz.rwms.logistics.inquiry.mapper.RentalInquiryResponseMapper;
import dev.buhanzaz.rwms.logistics.inquiry.repository.RentalSettingsRepository;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns logistics-local rental settings used while validating rental inquiry and order workflows.
 */
@Service
@RequiredArgsConstructor
public class RentalSettingsService {
  private static final Set<String> ADMIN_ROLES = Set.of("SYSTEM_ADMIN", "WMS_ADMIN");

  private final RentalSettingsRepository settings;
  private final RentalInquiryResponseMapper mapper;

  @Transactional
  public RentalSettingsResponse get(OrderActor actor) {
    RentalSettings value = loadOrCreate(actor);
    return mapper.toResponse(value);
  }

  @Transactional
  public RentalSettingsResponse update(
      OrderActor actor, UpdateRentalSettingsRequest request) {
    if (!ADMIN_ROLES.contains(actor.role()) || !actor.writeScope()) {
      throw new AccessDeniedException("Global rental settings administration is required");
    }
    RentalSettings value =
        settings
            .findForUpdate(RentalSettings.SINGLETON_ID)
            .orElseGet(() -> RentalSettings.defaults(actor.subjectId(), now()));
    if (value.getVersion() != request.expectedVersion()) {
      throw new dev.buhanzaz.rwms.logistics.order.service.OrderProblemException(
          org.springframework.http.HttpStatus.CONFLICT,
          "SETTINGS_VERSION_CONFLICT",
          "Настройки уже изменены другим пользователем");
    }
    value.update(
        request.chatSelectionHoldMinutes(),
        request.manualBookingHoldMinutes(),
        request.presentationHoldMinutes(),
        request.draftReservationHoldMinutes(),
        request.lateChangeNoticeDays(),
        request.lateChangeFeeMode(),
        request.lateChangeFeeValue(),
        request.rentalSupportPhone(),
        actor.subjectId(),
        now());
    return mapper.toResponse(settings.saveAndFlush(value));
  }

  @Transactional
  public int chatSelectionHoldMinutes(OrderActor actor) {
    return loadOrCreate(actor).getChatSelectionHoldMinutes();
  }

  @Transactional
  public int presentationHoldMinutes(OrderActor actor) {
    return loadOrCreate(actor).getPresentationHoldMinutes();
  }

  @Transactional
  public int manualBookingHoldMinutes(OrderActor actor) {
    return loadOrCreate(actor).getManualBookingHoldMinutes();
  }

  @Transactional
  public int draftReservationHoldMinutes(OrderActor actor) {
    return loadOrCreate(actor).getDraftReservationHoldMinutes();
  }

  /** Reads one immutable policy revision without creating settings or applying any fee. */
  @Transactional(readOnly = true)
  public LateChangePolicy lateChangePolicy() {
    return settings
        .findById(RentalSettings.SINGLETON_ID)
        .map(
            value ->
                new LateChangePolicy(
                    value.getVersion(),
                    value.getLateChangeNoticeDays(),
                    value.getLateChangeFeeMode(),
                    value.getLateChangeFeeValue(),
                    value.getRentalSupportPhone()))
        .orElseGet(() -> new LateChangePolicy(0, 2, null, null, null));
  }

  /** Notice uses warehouse-local calendar days; null fee fields mean unconfigured, not free. */
  public record LateChangePolicy(
      long settingsVersion,
      int noticeDays,
      LateChangeFeeMode feeMode,
      BigDecimal feeValue,
      String supportPhone) {}

  private RentalSettings loadOrCreate(OrderActor actor) {
    return settings
        .findById(RentalSettings.SINGLETON_ID)
        .orElseGet(
            () ->
                settings.saveAndFlush(
                    RentalSettings.defaults(actor.subjectId(), now())));
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }
}
