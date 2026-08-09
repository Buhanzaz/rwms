package dev.buhanzaz.rwms.asset.service;

/**
 * Signals a asset workflow failure that the HTTP boundary maps to a stable response.
 */
public class AssetConflictException extends RuntimeException {
  public AssetConflictException(String message) { super(message); }
}
