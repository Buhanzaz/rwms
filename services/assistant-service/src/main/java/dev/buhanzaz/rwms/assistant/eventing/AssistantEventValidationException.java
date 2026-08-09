package dev.buhanzaz.rwms.assistant.eventing;

/** Safe-coded rejection of an invalid booking-event source record or canonical envelope. */
public final class AssistantEventValidationException extends RuntimeException {
  private final String code;

  public AssistantEventValidationException(String code) {
    super("Assistant booking event was rejected: " + code);
    this.code = code;
  }

  public AssistantEventValidationException(String code, Throwable cause) {
    super("Assistant booking event was rejected: " + code, cause);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
