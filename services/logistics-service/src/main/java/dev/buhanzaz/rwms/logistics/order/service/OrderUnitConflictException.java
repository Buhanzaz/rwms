package dev.buhanzaz.rwms.logistics.order.service;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class OrderUnitConflictException extends OrderProblemException {
  private final UUID orderId;
  private final UUID unitId;

  public OrderUnitConflictException(
      UUID orderId, UUID unitId, String code, String message) {
    super(HttpStatus.CONFLICT, code, message);
    this.orderId = orderId;
    this.unitId = unitId;
  }

  public UUID orderId() {
    return orderId;
  }

  public UUID unitId() {
    return unitId;
  }
}
