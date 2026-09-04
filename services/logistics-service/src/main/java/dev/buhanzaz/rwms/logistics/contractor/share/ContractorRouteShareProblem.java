package dev.buhanzaz.rwms.logistics.contractor.share;

import org.springframework.http.HttpStatus;

/** Safe Russian domain failure used only by the contractor public-route boundary. */
public final class ContractorRouteShareProblem extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  private ContractorRouteShareProblem(HttpStatus status, String code, String detail) {
    super(detail);
    this.status = status;
    this.code = code;
  }

  /** Creates the indistinguishable response used for invalid, expired or reassigned links. */
  public static ContractorRouteShareProblem notFound() {
    return new ContractorRouteShareProblem(
        HttpStatus.NOT_FOUND,
        "CONTRACTOR_ROUTE_SHARE_NOT_FOUND",
        "Маршрут не найден или ссылка больше не действует");
  }

  /** Creates a typed safe business conflict without exposing a downstream response body. */
  public static ContractorRouteShareProblem conflict(String code, String detail) {
    return new ContractorRouteShareProblem(HttpStatus.CONFLICT, code, detail);
  }

  /** Creates a bounded Russian request-validation failure for anonymous binary commands. */
  public static ContractorRouteShareProblem badRequest(String code, String detail) {
    return new ContractorRouteShareProblem(HttpStatus.BAD_REQUEST, code, detail);
  }

  /** Rejects any contractor evidence representation outside the two approved image formats. */
  public static ContractorRouteShareProblem unsupportedMedia() {
    return new ContractorRouteShareProblem(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        "CONTRACTOR_ROUTE_EVIDENCE_MEDIA_UNSUPPORTED",
        "Поддерживаются только фотографии JPEG и WebP");
  }

  /** Creates a safe temporary dependency failure suitable for a user retry. */
  public static ContractorRouteShareProblem unavailable() {
    return new ContractorRouteShareProblem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "CONTRACTOR_ROUTE_TEMPORARILY_UNAVAILABLE",
        "Маршрут временно недоступен; повторите попытку позже");
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }
}
