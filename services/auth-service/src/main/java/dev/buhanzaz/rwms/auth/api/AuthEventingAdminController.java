package dev.buhanzaz.rwms.auth.api;

import dev.buhanzaz.rwms.auth.eventing.AuthOutboxStore;
import dev.buhanzaz.rwms.auth.eventing.AuthReplayCoordinator;
import dev.buhanzaz.rwms.auth.eventing.AuthReplayVerifier;
import dev.buhanzaz.rwms.auth.eventing.AuthSanitizedDltStore;
import dev.buhanzaz.rwms.auth.eventing.AuthAggregateType;
import dev.buhanzaz.rwms.auth.eventing.AuthShadowReconciler;
import dev.buhanzaz.rwms.auth.eventing.AuthSubjectProfileStore;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/eventing")
@RequiredArgsConstructor
public class AuthEventingAdminController {

    private final AuthOutboxStore outbox;
    private final AuthSanitizedDltStore deadLetters;
    private final AuthShadowReconciler shadowReconciler;
    private final AuthReplayCoordinator replayCoordinator;
    private final AuthSubjectProfileStore profiles;

    @PostMapping("/outbox/{eventId}/requeue")
    public ResponseEntity<Void> requeue(
            @PathVariable UUID eventId, @Valid @RequestBody RequeueOutboxRequest request) {
        if (!outbox.requeue(eventId, request.expectedAttemptCount())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Outbox row is not eligible for the requested recovery version");
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sanitized-dlt/{dltId}/requeue")
    public ResponseEntity<Void> requeueDeadLetter(
            @PathVariable UUID dltId, @Valid @RequestBody RequeueOutboxRequest request) {
        if (!deadLetters.requeue(dltId, request.expectedAttemptCount())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Dead-letter row is not eligible for the requested recovery version");
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/shadow/{aggregateType}/{aggregateId}/reconcile")
    public AuthShadowReconciler.Result reconcileShadow(
            @PathVariable AuthAggregateType aggregateType,
            @PathVariable UUID aggregateId,
            @Valid @RequestBody ReconcileAuthShadowRequest request,
            Authentication authentication) {
        UUID actorId = profiles.findSubjectIdByUsername(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "Operator has no canonical auth subject"));
        return shadowReconciler.reconcile(
                aggregateType,
                aggregateId,
                request.expectedCheckpointVersion(),
                request.reason(),
                actorId);
    }

    @PostMapping("/shadow/rebuild")
    public AuthReplayVerifier.ReplayParityResult rebuildShadow(
            @Valid @RequestBody ReplayAuthShadowRequest request,
            Authentication authentication) {
        UUID actorId = profiles.findSubjectIdByUsername(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "Operator has no canonical auth subject"));
        return replayCoordinator.rebuild(
                request.operationId(), actorId, request.reason());
    }
}
