package dev.buhanzaz.rwms.logistics.customer.service;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerSessionState;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerRentalSessionRepository;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short transactional store for customer cart command preparation and finalization. */
@Service
@RequiredArgsConstructor
public class CustomerRentalSessionStore {
  private final CustomerRentalSessionRepository sessions;
  private final LogisticsTransactionLock transactionLock;

  /** Creates the cart after the existing inquiry has been durably created. */
  @Transactional
  public CustomerRentalSession create(UUID inquiryId, UUID subjectId, UUID warehouseId) {
    transactionLock.acquire("customer-rental-session:" + inquiryId);
    CustomerRentalSession existing = sessions.findByInquiryId(inquiryId).orElse(null);
    if (existing != null) {
      if (!subjectId.equals(existing.getCustomerSubjectId())
          || !warehouseId.equals(existing.getWarehouseId())) {
        throw conflict(
            "CUSTOMER_INQUIRY_IDEMPOTENCY_CONFLICT",
            "Idempotency-Key уже использован для другой корзины");
      }
      return existing;
    }
    return sessions.saveAndFlush(CustomerRentalSession.create(inquiryId, subjectId, warehouseId));
  }

  /** Claims or recognizes an idempotent cabin-selection command under a row lock. */
  @Transactional
  public SelectionPreparation prepareSelection(
      UUID subjectId,
      UUID inquiryId,
      long expectedVersion,
      UUID commandKey,
      String commandSha256) {
    CustomerRentalSession session = locked(subjectId, inquiryId);
    if (commandKey.equals(session.getLastSelectionKey())) {
      if (!Objects.equals(commandSha256, session.getLastSelectionSha256())) {
        throw conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key уже использован для другой команды");
      }
      return new SelectionPreparation(session, true, false, commandKey);
    }
    if (session.getState() == CustomerSessionState.SELECTION_PENDING) {
      if (!Objects.equals(commandSha256, session.getPendingCommandSha256())) {
        throw conflict("CUSTOMER_CART_BUSY", "Другая команда корзины ещё выполняется");
      }
      return new SelectionPreparation(
          session, false, true, session.getPendingCommandKey());
    }
    translateVersion(() -> session.beginSelection(expectedVersion, commandKey, commandSha256));
    return new SelectionPreparation(
        sessions.saveAndFlush(session), false, false, commandKey);
  }

  /** Finalizes a successful asset hold command and prunes orphaned furniture intent. */
  @Transactional
  public CustomerRentalSession completeSelection(
      UUID subjectId,
      UUID inquiryId,
      UUID commandKey,
      String commandSha256,
      String equipmentJson,
      String rentalTermsJson) {
    CustomerRentalSession session = locked(subjectId, inquiryId);
    session.completeSelection(commandKey, commandSha256, equipmentJson, rentalTermsJson);
    return sessions.saveAndFlush(session);
  }

  /** Clears the pending marker after a proven failed remote command. */
  @Transactional
  public void failSelection(
      UUID subjectId, UUID inquiryId, UUID commandKey, String commandSha256) {
    CustomerRentalSession session = locked(subjectId, inquiryId);
    if (session.getState() == CustomerSessionState.SELECTION_PENDING) {
      session.failSelection(commandKey, commandSha256);
      sessions.saveAndFlush(session);
    }
  }

  /** Replaces the complete furniture intent under an optimistic row fence. */
  @Transactional
  public CustomerRentalSession replaceEquipment(
      UUID subjectId, UUID inquiryId, long expectedVersion, String equipmentJson) {
    CustomerRentalSession session = locked(subjectId, inquiryId);
    translateVersion(() -> session.replaceEquipment(expectedVersion, equipmentJson));
    return sessions.saveAndFlush(session);
  }

  /** Replaces the complete rental-term intent under an optimistic row fence. */
  @Transactional
  public CustomerRentalSession replaceRentalTerms(
      UUID subjectId, UUID inquiryId, long expectedVersion, String rentalTermsJson) {
    CustomerRentalSession session = locked(subjectId, inquiryId);
    translateVersion(() -> session.replaceRentalTerms(expectedVersion, rentalTermsJson));
    return sessions.saveAndFlush(session);
  }

  /** Binds a route-capacity hold to the cart under its latest version. */
  @Transactional
  public CustomerRentalSession selectSlot(
      UUID subjectId, UUID inquiryId, long expectedVersion, UUID slotId) {
    CustomerRentalSession session = locked(subjectId, inquiryId);
    translateVersion(() -> session.selectDeliverySlot(expectedVersion, slotId));
    return sessions.saveAndFlush(session);
  }

  /** Claims checkout and returns its immutable intent snapshot. */
  @Transactional
  public CheckoutPreparation prepareCheckout(
      UUID subjectId,
      UUID inquiryId,
      long expectedVersion,
      UUID commandKey,
      String commandSha256) {
    CustomerRentalSession session = locked(subjectId, inquiryId);
    if (session.getCheckoutCommandKey() != null
        && commandKey.equals(session.getCheckoutCommandKey())) {
      if (!Objects.equals(commandSha256, session.getCheckoutCommandSha256())) {
        throw conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key уже использован для другой команды");
      }
      return new CheckoutPreparation(
          session,
          session.getState() == CustomerSessionState.BOOKED,
          session.getState() == CustomerSessionState.CHECKOUT_PENDING,
          session.getCheckoutCommandKey());
    }
    if (session.getCheckoutCommandKey() != null
        && Objects.equals(commandSha256, session.getCheckoutCommandSha256())
        && (session.getState() == CustomerSessionState.CHECKOUT_PENDING
            || session.getState() == CustomerSessionState.BOOKED)) {
      return new CheckoutPreparation(
          session,
          session.getState() == CustomerSessionState.BOOKED,
          session.getState() == CustomerSessionState.CHECKOUT_PENDING,
          session.getCheckoutCommandKey());
    }
    if (session.getState() == CustomerSessionState.CHECKOUT_PENDING) {
      throw conflict("CUSTOMER_CART_BUSY", "Другая команда оформления ещё выполняется");
    }
    translateVersion(() -> session.beginCheckout(expectedVersion, commandKey, commandSha256));
    return new CheckoutPreparation(
        sessions.saveAndFlush(session), false, false, commandKey);
  }

  /** Stores the durable presentation-booking receipt for retry reconciliation. */
  @Transactional
  public CustomerRentalSession recordPendingBooking(
      UUID subjectId,
      UUID inquiryId,
      UUID commandKey,
      String commandSha256,
      UUID bookingId,
      UUID orderId,
      String presentationToken) {
    CustomerRentalSession session = locked(subjectId, inquiryId);
    session.recordPendingBooking(
        commandKey, commandSha256, bookingId, orderId, presentationToken);
    return sessions.saveAndFlush(session);
  }

  /** Marks a customer cart booked once the existing booking saga completes. */
  @Transactional
  public CustomerRentalSession completeBooking(
      UUID subjectId, UUID inquiryId, UUID bookingId, UUID orderId) {
    CustomerRentalSession session = locked(subjectId, inquiryId);
    session.completeBooking(bookingId, orderId);
    return sessions.saveAndFlush(session);
  }

  /** Makes a rejected booking correctable while retaining its durable upstream receipt. */
  @Transactional
  public CustomerRentalSession rejectBooking(
      UUID subjectId, UUID inquiryId, UUID bookingId) {
    CustomerRentalSession session = locked(subjectId, inquiryId);
    session.rejectBooking(bookingId);
    return sessions.saveAndFlush(session);
  }

  /** Returns one customer-owned cart without a write lock. */
  @Transactional(readOnly = true)
  public CustomerRentalSession required(UUID subjectId, UUID inquiryId) {
    return sessions
        .findByInquiryIdAndCustomerSubjectId(inquiryId, subjectId)
        .orElseThrow(CustomerRentalSessionStore::notFound);
  }

  /** Lists all carts of one authenticated customer in reverse creation order. */
  @Transactional(readOnly = true)
  public List<CustomerRentalSession> list(UUID subjectId) {
    return sessions.findAllByCustomerSubjectIdOrderByCreatedAtDescIdDesc(subjectId);
  }

  /** Returns the oldest deterministic batch of at most 100 pending checkout carts. */
  @Transactional(readOnly = true)
  public List<CustomerRentalSession> pendingBookings() {
    return sessions.findAllByStateOrderByUpdatedAtAscIdAsc(
        CustomerSessionState.CHECKOUT_PENDING, PageRequest.of(0, 100));
  }

  private CustomerRentalSession locked(UUID subjectId, UUID inquiryId) {
    CustomerRentalSession session =
        sessions.findByInquiryIdForUpdate(inquiryId).orElseThrow(CustomerRentalSessionStore::notFound);
    if (!subjectId.equals(session.getCustomerSubjectId())) throw notFound();
    return session;
  }

  private static void translateVersion(Runnable transition) {
    try {
      transition.run();
    } catch (IllegalArgumentException exception) {
      String code =
          exception.getMessage() != null && exception.getMessage().contains("version")
              ? "CUSTOMER_CART_VERSION_CONFLICT"
              : "CUSTOMER_CART_COMMAND_INVALID";
      throw conflict(code, "Корзина уже изменилась; обновите данные и повторите действие");
    } catch (IllegalStateException exception) {
      throw conflict("CUSTOMER_CART_BUSY", "Корзина сейчас недоступна для изменения");
    }
  }

  private static OrderProblemException notFound() {
    return new OrderProblemException(HttpStatus.NOT_FOUND, "CUSTOMER_CART_NOT_FOUND", "Корзина не найдена");
  }

  private static OrderProblemException conflict(String code, String message) {
    return new OrderProblemException(HttpStatus.CONFLICT, code, message);
  }

  /** Prepared selection state distinguishing completed and pending idempotent replays. */
  public record SelectionPreparation(
      CustomerRentalSession session,
      boolean completedReplay,
      boolean pendingReplay,
      UUID commandKey) {}

  /** Prepared checkout state distinguishing terminal and in-flight idempotent replays. */
  public record CheckoutPreparation(
      CustomerRentalSession session,
      boolean completedReplay,
      boolean pendingReplay,
      UUID commandKey) {}
}
