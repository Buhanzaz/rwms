package dev.buhanzaz.rwms.asset.service;

/**
 * Signals a asset workflow failure that the HTTP boundary maps to a stable response.
 */
public class AssetNotFoundException extends RuntimeException {
  public AssetNotFoundException(String message) { super(message); }
}
