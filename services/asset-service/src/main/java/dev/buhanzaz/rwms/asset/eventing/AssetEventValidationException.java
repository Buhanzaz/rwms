package dev.buhanzaz.rwms.asset.eventing;

/** Non-retryable envelope or payload rejection; only its hash reaches the sanitized DLT. */
public class AssetEventValidationException extends RuntimeException {
  public AssetEventValidationException(String message, Throwable cause) { super(message, cause); }
  public AssetEventValidationException(String message) { super(message); }
}
