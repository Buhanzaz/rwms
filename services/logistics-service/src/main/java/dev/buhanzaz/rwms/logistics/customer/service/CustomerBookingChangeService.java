package dev.buhanzaz.rwms.logistics.customer.service;

import dev.buhanzaz.rwms.logistics.customer.api.CustomerBookingChangeApiModels.CreateCustomerBookingChangeQuoteRequest;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerBookingChangeApiModels.CustomerBookingChangeQuoteResponse;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reads canonical warehouse timezone before taking any fee or booking mutation locks. */
@Service
@RequiredArgsConstructor
public class CustomerBookingChangeService {
  private final CustomerBookingChangeChargeStore store;
  private final LogisticsDependencyGateway dependencies;

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public CustomerBookingChangeQuoteResponse create(
      CustomerIdentity identity,
      UUID bookingId,
      UUID key,
      CreateCustomerBookingChangeQuoteRequest request) {
    String hash =
        hash(
            bookingId
                + "\n"
                + request.expectedVersion()
                + "\n"
                + request.operation()
                + "\n"
                + request.slotId()
                + "\n"
                + request.slotVersion());
    var replay = store.replay(identity, key, bookingId, hash);
    if (replay.isPresent()) return replay.get();
    var session = store.ownedSession(identity, bookingId);
    var warehouse = dependencies.readWarehouseIdentity(session.getWarehouseId());
    try {
      if (warehouse == null || warehouse.timeZone() == null)
        throw new DateTimeException("Missing warehouse timezone");
      ZoneId.of(warehouse.timeZone());
    } catch (DateTimeException exception) {
      throw new OrderProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "CUSTOMER_CHANGE_TIME_ZONE_UNAVAILABLE",
          "Не удалось получить часовой пояс склада. Повторите позже");
    }
    return store.create(identity, bookingId, key, hash, request, warehouse);
  }

  public CustomerBookingChangeQuoteResponse get(
      CustomerIdentity identity, UUID bookingId, UUID quoteId) {
    return store.get(identity, bookingId, quoteId);
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
