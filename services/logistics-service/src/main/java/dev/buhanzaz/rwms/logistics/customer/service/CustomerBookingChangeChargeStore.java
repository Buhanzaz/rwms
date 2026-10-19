package dev.buhanzaz.rwms.logistics.customer.service;

import dev.buhanzaz.rwms.logistics.customer.api.CustomerBookingChangeApiModels.CreateCustomerBookingChangeQuoteRequest;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerBookingChangeApiModels.CustomerBookingChangeQuoteResponse;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerBookingChangeCharge;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerBookingMutationOperation;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerChangeApplicationState;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerChangeSettlement;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotState;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerSessionState;
import dev.buhanzaz.rwms.logistics.customer.mapper.CustomerBookingChangeResponseMapper;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerBookingChangeChargeRepository;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerDeliverySlotRepository;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerRentalSessionRepository;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalSettings;
import dev.buhanzaz.rwms.logistics.inquiry.repository.RentalSettingsRepository;
import dev.buhanzaz.rwms.logistics.inquiry.service.RentalSettingsService;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseIdentity;
import dev.buhanzaz.rwms.logistics.order.domain.OrderAuditEventType;
import dev.buhanzaz.rwms.logistics.order.service.OrderAuditService;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Short logistics-local transactions for exact quote consent and mutation-linked settlement. */
@Service
@RequiredArgsConstructor
public class CustomerBookingChangeChargeStore {
  private final CustomerBookingChangeChargeRepository charges;
  private final CustomerRentalSessionRepository sessions;
  private final CustomerDeliverySlotRepository slots;
  private final RentalSettingsService policies;
  private final RentalSettingsRepository settings;
  private final CustomerBookingChangeResponseMapper mapper;
  private final LogisticsTransactionLock locks;
  private final OrderAuditService audit;
  private final Clock clock;

  @Value("${rwms.logistics.customer.test-change-payment-enabled:true}")
  private boolean testPaymentEnabled;

  /** Exact replay is available without a warehouse dependency round trip. */
  @Transactional(readOnly = true)
  public Optional<CustomerBookingChangeQuoteResponse> replay(
      CustomerIdentity identity, UUID key, UUID bookingId, String hash) {
    return charges
        .findByCustomerSubjectIdAndIdempotencyKey(identity.subjectId(), key)
        .map(
            charge -> {
              requireReplay(charge, bookingId, hash);
              return response(charge);
            });
  }

  @Transactional(readOnly = true)
  public CustomerRentalSession ownedSession(CustomerIdentity identity, UUID bookingId) {
    return sessions
        .findByBookingIdAndCustomerSubjectId(bookingId, identity.subjectId())
        .orElseThrow(CustomerBookingChangeChargeStore::notFound);
  }

  /** Rechecks ownership and source/target fences after reading canonical warehouse identity. */
  @Transactional
  public CustomerBookingChangeQuoteResponse create(
      CustomerIdentity identity,
      UUID bookingId,
      UUID key,
      String hash,
      CreateCustomerBookingChangeQuoteRequest request,
      WarehouseIdentity warehouse) {
    locks.acquire("customer-change-quote:" + identity.subjectId() + ":" + key);
    var replay = charges.findByCustomerSubjectIdAndIdempotencyKey(identity.subjectId(), key);
    if (replay.isPresent()) {
      requireReplay(replay.get(), bookingId, hash);
      return response(replay.get());
    }
    CustomerRentalSession session =
        sessions
            .findByBookingIdForUpdate(bookingId)
            .orElseThrow(CustomerBookingChangeChargeStore::notFound);
    if (!identity.subjectId().equals(session.getCustomerSubjectId())) throw notFound();
    if (session.getVersion() != request.expectedVersion()
        || session.getState() != CustomerSessionState.BOOKED) throw stale();
    if (!warehouse.id().equals(session.getWarehouseId())) throw stale();
    CustomerDeliverySlot original =
        slots
            .findById(session.getDeliverySlotId())
            .orElseThrow(CustomerBookingChangeChargeStore::stale);
    if (original.getState() != CustomerDeliverySlotState.CONFIRMED
        || !bookingId.equals(original.getBookingId())
        || !session.getOrderId().equals(original.getOrderId())
        || !session.getCustomerSubjectId().equals(original.getCustomerSubjectId())
        || !session.getWarehouseId().equals(original.getWarehouseId())
        || !session.getInquiryId().equals(original.getInquiryId())) throw stale();
    CustomerDeliverySlot replacement = null;
    if (request.operation() == CustomerBookingMutationOperation.RESCHEDULE) {
      if (request.slotId() == null || request.slotVersion() == null) throw invalid();
      replacement =
          slots.findById(request.slotId()).orElseThrow(CustomerBookingChangeChargeStore::stale);
      if (replacement.getId().equals(original.getId())
          || replacement.getVersion() != request.slotVersion()
          || !identity.subjectId().equals(replacement.getCustomerSubjectId())
          || !session.getInquiryId().equals(replacement.getInquiryId())
          || !session.getWarehouseId().equals(replacement.getWarehouseId())
          || replacement.getState() != CustomerDeliverySlotState.OFFERED
          || !replacement.getExpiresAt().isAfter(now())) throw stale();
    } else if (request.slotId() != null || request.slotVersion() != null) throw invalid();
    var policy = policies.lateChangePolicy();
    var assessment =
        CustomerBookingChangePolicy.assess(
            policy,
            original.getDeliveryDate(),
            original.getDeliveryPriceRubles(),
            warehouse.timeZone(),
            clock.instant());
    var charge =
        CustomerBookingChangeCharge.quote(
            session,
            original,
            replacement,
            request.operation(),
            key,
            hash,
            warehouse.version(),
            warehouse.timeZone(),
            policy.settingsVersion(),
            policy.noticeDays(),
            policy.feeMode(),
            policy.feeValue(),
            assessment.noticeDate(),
            assessment.amountRubles(),
            assessment.settlement(),
            policy.supportPhone(),
            assessment.expiresAt(),
            now());
    return response(charges.saveAndFlush(charge));
  }

  /** Reads the exact quote result, never guesses payment from the booking's status. */
  @Transactional(readOnly = true)
  public CustomerBookingChangeQuoteResponse get(
      CustomerIdentity identity, UUID bookingId, UUID quoteId) {
    var charge = charges.findById(quoteId).orElseThrow(CustomerBookingChangeChargeStore::notFound);
    if (!charge.getCustomerSubjectId().equals(identity.subjectId())
        || !charge.getBookingId().equals(bookingId)) throw notFound();
    return response(charge);
  }

  /**
   * Requires the caller's existing session/slot locks. Settings lock prevents fee changes between
   * this check and owner commit; no money is marked paid until completeApplication runs.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public CustomerBookingChangeCharge admit(
      CustomerRentalSession session,
      CustomerDeliverySlot original,
      CustomerBookingMutationOperation operation,
      UUID replacementId,
      Long replacementVersion,
      UUID quoteId,
      Long expectedQuoteVersion,
      boolean testPaymentRequested) {
    if (quoteId == null || expectedQuoteVersion == null)
      throw conflict(
          "CUSTOMER_CHANGE_QUOTE_REQUIRED", "Перед изменением подтвердите расчёт неустойки");
    var charge =
        charges.findForUpdate(quoteId).orElseThrow(CustomerBookingChangeChargeStore::notFound);
    if (!charge.getCustomerSubjectId().equals(session.getCustomerSubjectId())
        || !charge.getBookingId().equals(session.getBookingId())) throw notFound();
    if (charge.getVersion() != expectedQuoteVersion
        || charge.getApplicationState() != CustomerChangeApplicationState.OFFERED
        || charge.getBookingVersion() != session.getVersion()
        || !charge.getOldSlotId().equals(original.getId())
        || charge.getOperation() != operation
        || !Objects.equals(charge.getSlotId(), replacementId)
        || !Objects.equals(charge.getSlotVersion(), replacementVersion)
        || !charge.getExpiresAt().isAfter(now())) throw stale();
    var currentPolicy = settings.findForUpdate(RentalSettings.SINGLETON_ID);
    if (!charge.getExpiresAt().isAfter(now())) throw stale();
    if (currentPolicy.map(RentalSettings::getVersion).orElse(0L) != charge.getPolicyVersion())
      throw stale();
    if (currentPolicy.isPresent()) {
      var policy = currentPolicy.get();
      if (policy.getLateChangeNoticeDays() != charge.getNoticeDays()
          || policy.getLateChangeFeeMode() != charge.getFeeMode()
          || (policy.getLateChangeFeeValue() == null
              ? charge.getFeeValue() != null
              : charge.getFeeValue() == null
                  || policy.getLateChangeFeeValue().compareTo(charge.getFeeValue()) != 0))
        throw stale();
    }
    if (charge.getSettlement() == CustomerChangeSettlement.POLICY_UNCONFIGURED) {
      throw conflict(
          "CUSTOMER_CHANGE_POLICY_UNCONFIGURED",
          "Неустойка пока не настроена. Свяжитесь с менеджером");
    }
    if (charge.getSettlement() == CustomerChangeSettlement.PAYMENT_REQUIRED
        && (!testPaymentRequested || !testPaymentEnabled)) {
      throw conflict(
          "CUSTOMER_CHANGE_PAYMENT_REQUIRED", "Требуется подтверждение тестовой оплаты неустойки");
    }
    return charge;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void bind(
      CustomerBookingChangeCharge charge, UUID mutationId, boolean testPaymentRequested) {
    charge.beginApplication(mutationId, testPaymentRequested);
    charges.saveAndFlush(charge);
  }

  /** The caller has completed the exact mutation in this same transaction. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void complete(UUID mutationId) {
    charges
        .findByMutationId(mutationId)
        .ifPresent(
            charge -> {
              boolean alreadyApplied =
                  charge.getApplicationState() == CustomerChangeApplicationState.APPLIED;
              charge.completeApplication(mutationId, now());
              charges.saveAndFlush(charge);
              if (!alreadyApplied)
                audit.appendForActor(
                    charge.getOrderId(),
                    OrderAuditEventType.ORDER_CHANGED,
                    charge.getCustomerSubjectId(),
                    "CUSTOMER",
                    "CUSTOMER_BOOKING_CHANGE_CHARGE",
                    charge.getId().toString(),
                    Map.of(),
                    Map.of(
                        "settlement",
                        charge.getSettlement().name(),
                        "amountRubles",
                        payableAmount(charge).toString(),
                        "mutationId",
                        mutationId.toString(),
                        "testPayment",
                        charge.isTestPaymentRequested()));
            });
  }

  CustomerBookingChangeQuoteResponse response(CustomerBookingChangeCharge charge) {
    return mapper.toResponse(
        charge,
        testPaymentEnabled
            && charge.getApplicationState() == CustomerChangeApplicationState.OFFERED
            && charge.getSettlement() == CustomerChangeSettlement.PAYMENT_REQUIRED,
        payableAmount(charge));
  }

  private static Long payableAmount(CustomerBookingChangeCharge charge) {
    return charge.getSettlement() == CustomerChangeSettlement.WAIVED
        ? Long.valueOf(0)
        : charge.getAmountRubles();
  }

  private static void requireReplay(
      CustomerBookingChangeCharge charge, UUID bookingId, String hash) {
    if (!charge.getBookingId().equals(bookingId) || !charge.getRequestSha256().equals(hash)) {
      throw conflict("IDEMPOTENCY_KEY_REUSED", "Ключ уже использован для другого расчёта");
    }
  }

  private OffsetDateTime now() {
    return clock.instant().atOffset(ZoneOffset.UTC);
  }

  static OrderProblemException stale() {
    return conflict(
        "CUSTOMER_CHANGE_QUOTE_STALE",
        "Расчёт изменился или истёк. Обновите бронирование и выберите время заново");
  }

  static OrderProblemException notFound() {
    return new OrderProblemException(
        HttpStatus.NOT_FOUND, "CUSTOMER_CHANGE_QUOTE_NOT_FOUND", "Расчёт не найден");
  }

  private static OrderProblemException invalid() {
    return new OrderProblemException(
        HttpStatus.BAD_REQUEST,
        "CUSTOMER_CHANGE_QUOTE_INVALID",
        "Перенос требует точный новый слот, отмена не содержит нового слота");
  }

  static OrderProblemException conflict(String code, String message) {
    return new OrderProblemException(HttpStatus.CONFLICT, code, message);
  }
}
