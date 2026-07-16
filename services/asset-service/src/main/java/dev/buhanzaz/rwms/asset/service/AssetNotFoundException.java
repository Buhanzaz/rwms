package dev.buhanzaz.rwms.asset.service;

public class AssetNotFoundException extends RuntimeException {
  public AssetNotFoundException(String message) { super(message); }
}
