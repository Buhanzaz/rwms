package dev.buhanzaz.rwms.asset.service;

/**
 * Signals a asset workflow failure that the HTTP boundary maps to a stable response.
 */
public class OrderUnitReservationConflictException extends RuntimeException {
  private final String code;

  public OrderUnitReservationConflictException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
