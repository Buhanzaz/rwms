package dev.buhanzaz.rwms.auth.api;

import dev.buhanzaz.rwms.auth.service.WorkerCredentialService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Private service API for provisioning and operating worker-application credentials.
 *
 * <p>Credentials are managed by {@link WorkerCredentialService}; no endpoint returns a plaintext
 * password or password hash.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/internal/worker-credentials")
public class InternalWorkerCredentialController {

    private final WorkerCredentialService credentials;

    /**
     * Creates or reconfigures the credential bound to the supplied external worker identifier.
     *
     * @param workerId external worker identifier
     * @param request write-only configuration command
     * @return non-secret worker credential projection
     */
    @PutMapping("/{workerId}")
    WorkerCredentialResponse configure(
            @PathVariable String workerId,
            @Valid @RequestBody WorkerCredentialRequest request) {
        return credentials.configure(workerId, request);
    }

    /**
     * Replaces a worker credential's password and invalidates existing authorizations.
     *
     * @param workerId external worker identifier
     * @param request write-only password-replacement command
     * @return {@code 204 No Content} when the password has been processed
     */
    @PostMapping("/{workerId}/reset")
    ResponseEntity<Void> reset(
            @PathVariable String workerId,
            @Valid @RequestBody PasswordResetRequest request) {
        credentials.resetPassword(workerId, request.password());
        return ResponseEntity.noContent().build();
    }

    /**
     * Disables a worker credential; repeating the operation is safe for a missing or disabled worker.
     *
     * @param workerId external worker identifier
     * @return {@code 204 No Content} after the idempotent operation
     */
    @PostMapping("/{workerId}/disable")
    ResponseEntity<Void> disable(@PathVariable String workerId) {
        credentials.disable(workerId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Enables an existing disabled worker credential.
     *
     * @param workerId external worker identifier
     * @return {@code 204 No Content} after the worker is enabled
     */
    @PostMapping("/{workerId}/enable")
    ResponseEntity<Void> enable(@PathVariable String workerId) {
        credentials.enable(workerId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Deletes a worker credential and invalidates authorizations if the worker exists.
     *
     * @param workerId external worker identifier
     * @return {@code 204 No Content} after the idempotent operation
     */
    @DeleteMapping("/{workerId}")
    ResponseEntity<Void> delete(@PathVariable String workerId) {
        credentials.delete(workerId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Returns the non-secret operational status of one worker credential.
     *
     * @param workerId external worker identifier
     * @return current credential projection
     */
    @GetMapping("/{workerId}/status")
    WorkerCredentialResponse status(@PathVariable String workerId) {
        return credentials.status(workerId);
    }
}
