package dev.buhanzaz.rwms.logistics.order.service;

import org.springframework.http.HttpStatus;

public class OrderProblemException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  public OrderProblemException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }
}
