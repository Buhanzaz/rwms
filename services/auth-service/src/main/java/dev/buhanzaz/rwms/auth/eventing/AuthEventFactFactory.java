package dev.buhanzaz.rwms.auth.eventing;

import dev.buhanzaz.rwms.auth.domain.AuthSubject;
import dev.buhanzaz.rwms.auth.eventing.AuthEventPayloads.UserAuthorizationFact;
import dev.buhanzaz.rwms.auth.eventing.AuthEventPayloads.WarehouseGrantFact;
import dev.buhanzaz.rwms.auth.eventing.AuthEventPayloads.WorkerAccessFact;
import dev.buhanzaz.rwms.auth.eventing.AuthEventPayloads.WorkerCredentialFactStatus;
import dev.buhanzaz.rwms.auth.repository.UserWarehouseAccessRepository;
import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the current safe event fact for an auth aggregate from its authoritative live state.
 *
 * <p>The factory reads private profile, credential, and note vaults only to derive allowed IDs,
 * revisions, and authorization state. It never passes those private values into the resulting
 * Kafka payloads.
 */
@Service
@RequiredArgsConstructor
public class AuthEventFactFactory {

    private final UserWarehouseAccessRepository accesses;
    private final AuthSubjectProfileStore profiles;
    private final AuthSubjectCredentialStore credentials;
    private final UserWarehouseAccessNoteStore notes;

    /**
     * Builds the user-authorization fact that represents the subject's current grants and role.
     *
     * @param subject managed user aggregate
     * @return a validated, safe user-authorization snapshot
     */
    @Transactional(readOnly = true)
    public UserAuthorizationFact userAuthorization(AuthSubject subject) {
        var profile = profiles.require(subject.getId());
        var accessNotes = notes.findAllByUserId(subject.getId());
        var grants = accesses.findAllByUserIdOrderByWarehouseId(subject.getId()).stream()
                .map(access -> {
                    var note = accessNotes.get(access.getId());
                    if (note == null) {
                        throw new IllegalStateException("Warehouse access note vault entry is missing");
                    }
                    return new WarehouseGrantFact(
                            access.getId(),
                            UUID.fromString(access.getWarehouseId()),
                            access.getAccessLevel(),
                            access.isActive(),
                            note.revision());
                })
                .toList();
        return new UserAuthorizationFact(
                subject.getId(),
                subject.isActive(),
                subject.isMobileAppAccess(),
                subject.getGlobalRole(),
                profile.revision(),
                grants);
    }

    /**
     * Builds the worker-access fact that represents the subject's current worker state.
     *
     * @param subject managed worker aggregate
     * @return a validated, safe worker-access snapshot
     */
    @Transactional(readOnly = true)
    public WorkerAccessFact workerAccess(AuthSubject subject) {
        var credential = credentials.require(subject.getId());
        return new WorkerAccessFact(
                subject.getId(),
                subject.getId(),
                UUID.fromString(subject.getWarehouseId()),
                subject.isActive(),
                WorkerCredentialFactStatus.valueOf(credential.status()));
    }

    /**
     * Produces an opaque actor reference suitable for an event envelope.
     *
     * @param subject acting auth aggregate
     * @return non-sensitive actor identity and profile revision
     */
    @Transactional(readOnly = true)
    public OpaqueActorReference actor(AuthSubject subject) {
        return new OpaqueActorReference(
                subject.getId().toString(),
                subject.getPrincipalType().name(),
                profiles.require(subject.getId()).revision().toString());
    }
}
