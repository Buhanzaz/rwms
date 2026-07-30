package dev.buhanzaz.rwms.taskboard.service;

import java.util.UUID;

public interface WorkerCredentialGateway {
  void configure(UUID workerId, UUID warehouseId, String appLogin, String password);

  void reset(UUID workerId, String password);

  void disable(UUID workerId);

  void enable(UUID workerId);

  void delete(UUID workerId);

  WorkerCredentialSnapshot status(UUID workerId, UUID expectedWarehouseId);

  record WorkerCredentialSnapshot(
      UUID workerId, UUID warehouseId, String appLogin, WorkerCredentialStatus status) {}

  enum WorkerCredentialStatus {
    ACTIVE,
    DISABLED,
    ABSENT
  }
}
