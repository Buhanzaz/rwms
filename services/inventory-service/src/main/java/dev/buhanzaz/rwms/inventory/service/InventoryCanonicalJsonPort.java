package dev.buhanzaz.rwms.inventory.service;

/**
 * Port for canonical inventory request serialization and stable fingerprint calculation.
 */
public interface InventoryCanonicalJsonPort {
  String sha256(Object value);
}
