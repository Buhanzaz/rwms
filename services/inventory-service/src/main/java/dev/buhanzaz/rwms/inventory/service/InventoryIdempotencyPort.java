package dev.buhanzaz.rwms.inventory.service;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Port for executing an inventory command once per stable idempotency identity.
 */
public interface InventoryIdempotencyPort {
  String REPLAY_ATTRIBUTE = InventoryIdempotencyPort.class.getName() + ".replayed";

  <T> T execute(
      UUID subjectId,
      String commandScope,
      UUID idempotencyKey,
      Object request,
      int responseStatus,
      Class<T> responseType,
      Supplier<T> command);
}
