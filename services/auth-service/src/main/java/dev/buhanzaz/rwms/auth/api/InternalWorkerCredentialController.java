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

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/internal/worker-credentials")
public class InternalWorkerCredentialController {

    private final WorkerCredentialService credentials;

    @PutMapping("/{workerId}")
    WorkerCredentialResponse configure(
            @PathVariable String workerId,
            @Valid @RequestBody WorkerCredentialRequest request) {
        return credentials.configure(workerId, request);
    }

    @PostMapping("/{workerId}/reset")
    ResponseEntity<Void> reset(
            @PathVariable String workerId,
            @Valid @RequestBody PasswordResetRequest request) {
        credentials.resetPassword(workerId, request.password());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{workerId}/disable")
    ResponseEntity<Void> disable(@PathVariable String workerId) {
        credentials.disable(workerId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{workerId}/enable")
    ResponseEntity<Void> enable(@PathVariable String workerId) {
        credentials.enable(workerId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{workerId}")
    ResponseEntity<Void> delete(@PathVariable String workerId) {
        credentials.delete(workerId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{workerId}/status")
    WorkerCredentialResponse status(@PathVariable String workerId) {
        return credentials.status(workerId);
    }
}
