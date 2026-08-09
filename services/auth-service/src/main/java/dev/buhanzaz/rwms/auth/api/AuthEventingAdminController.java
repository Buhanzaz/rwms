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

/**
 * Restricted operational API for recovering the authorization service's event delivery and
 * rebuilding its read-side authorization shadow.
 *
 * <p>The endpoints use expected recovery versions and canonical operator identity to prevent
 * accidental replay or recovery against a stale row.
 */
@RestController
@RequestMapping("/api/admin/eventing")
@RequiredArgsConstructor
public class AuthEventingAdminController {

    private final AuthOutboxStore outbox;
    private final AuthSanitizedDltStore deadLetters;
    private final AuthShadowReconciler shadowReconciler;
    private final AuthReplayCoordinator replayCoordinator;
    private final AuthSubjectProfileStore profiles;

    /**
     * Requeues an eligible authorization outbox row at the caller's expected attempt count.
     *
     * @param eventId outbox event identifier
     * @param request recovery command fenced by attempt count
     * @return {@code 204 No Content} when the row is eligible for requeueing
     */
    @PostMapping("/outbox/{eventId}/requeue")
    public ResponseEntity<Void> requeue(
            @PathVariable UUID eventId, @Valid @RequestBody RequeueOutboxRequest request) {
        if (!outbox.requeue(eventId, request.expectedAttemptCount())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Outbox row is not eligible for the requested recovery version");
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Requeues an eligible sanitized dead-letter row at the caller's expected attempt count.
     *
     * @param dltId sanitized dead-letter identifier
     * @param request recovery command fenced by attempt count
     * @return {@code 204 No Content} when the row is eligible for requeueing
     */
    @PostMapping("/sanitized-dlt/{dltId}/requeue")
    public ResponseEntity<Void> requeueDeadLetter(
            @PathVariable UUID dltId, @Valid @RequestBody RequeueOutboxRequest request) {
        if (!deadLetters.requeue(dltId, request.expectedAttemptCount())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Dead-letter row is not eligible for the requested recovery version");
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Reconciles one authorization aggregate's shadow projection from its canonical event stream.
     *
     * @param aggregateType aggregate stream family to reconcile
     * @param aggregateId aggregate identifier to reconcile
     * @param request recovery checkpoint and audit reason
     * @param authentication authenticated recovery operator
     * @return reconciliation result recorded by the shadow coordinator
     */
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

    /**
     * Rebuilds the authorization shadow under a recorded recovery operation identifier.
     *
     * @param request durable recovery-operation identifier and audit reason
     * @param authentication authenticated recovery operator
     * @return replay-parity result for the rebuilt shadow
     */
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
