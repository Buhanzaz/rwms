package dev.buhanzaz.rwms.auth.api;

public record WorkerCredentialResponse(
        String workerId,
        String warehouseId,
        String appLogin,
        WorkerCredentialStatus status) {

    public enum WorkerCredentialStatus {
        ACTIVE,
        DISABLED
    }
}
