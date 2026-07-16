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

@Service
@RequiredArgsConstructor
public class AuthEventFactFactory {

    private final UserWarehouseAccessRepository accesses;
    private final AuthSubjectProfileStore profiles;
    private final AuthSubjectCredentialStore credentials;
    private final UserWarehouseAccessNoteStore notes;

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
                subject.getGlobalRole(),
                profile.revision(),
                grants);
    }

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

    @Transactional(readOnly = true)
    public OpaqueActorReference actor(AuthSubject subject) {
        return new OpaqueActorReference(
                subject.getId().toString(),
                subject.getPrincipalType().name(),
                profiles.require(subject.getId()).revision().toString());
    }
}
