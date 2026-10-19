package dev.buhanzaz.rwms.auth.api;

/**
 * Non-secret operational projection of a worker application's credential.
 *
 * @param workerId external worker identifier
 * @param warehouseId warehouse currently bound to the worker
 * @param appLogin canonical worker-application login
 * @param status current authentication lifecycle state
 */
public record WorkerCredentialResponse(
        String workerId,
        String warehouseId,
        String appLogin,
        WorkerCredentialStatus status) {

    /** Lifecycle state reported for a worker credential without exposing credential material. */
    public enum WorkerCredentialStatus {
        /** The worker can authenticate with its configured credential. */
        ACTIVE,
        /** The worker credential is retained but may not authenticate. */
        DISABLED
    }
}
