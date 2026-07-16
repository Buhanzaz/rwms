package dev.buhanzaz.rwms.auth.eventing;

import dev.buhanzaz.rwms.auth.domain.AuthSubject;
import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import dev.buhanzaz.rwms.auth.domain.UserWarehouseAccess;
import dev.buhanzaz.rwms.auth.domain.WarehouseAccessLevel;
import dev.buhanzaz.rwms.auth.repository.AuthSubjectRepository;
import dev.buhanzaz.rwms.auth.repository.UserWarehouseAccessRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthProjectionWriter {

    private final AuthSubjectRepository subjects;
    private final UserWarehouseAccessRepository accesses;
    private final AuthSubjectProfileStore profiles;
    private final AuthSubjectCredentialStore credentials;
    private final UserWarehouseAccessNoteStore notes;

    @Transactional(propagation = Propagation.MANDATORY)
    public AuthSubject insertUser(
            String username,
            String passwordHash,
            String firstName,
            String lastName,
            String email,
            String timeZoneId,
            UserGlobalRole globalRole,
            boolean active) {
        var subject = new AuthSubject();
        subject.registerUser(
                username, passwordHash, firstName, lastName, email, timeZoneId, globalRole, active);
        return insert(subject);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AuthSubject insertWorker(
            String externalWorkerId,
            String warehouseId,
            String username,
            String passwordHash) {
        var subject = new AuthSubject();
        subject.registerWorker(externalWorkerId, warehouseId, username, passwordHash);
        return insert(subject);
    }

    private AuthSubject insert(AuthSubject subject) {
        AuthSubject saved = subjects.saveAndFlush(subject);
        profiles.replace(
                saved.getId(),
                saved.getUsername(),
                saved.getFirstName(),
                saved.getLastName(),
                saved.getEmail(),
                saved.getTimeZoneId(),
                saved.getExternalWorkerId());
        credentials.replace(saved.getId(), saved.getPasswordHash(), saved.isActive());
        return saved;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AuthSubject updateUser(
            AuthSubject subject,
            String username,
            String firstName,
            String lastName,
            String email,
            String timeZoneId,
            UserGlobalRole globalRole,
            boolean active,
            boolean profileChanged,
            boolean credentialStatusChanged) {
        subject.changeUserProfile(username, firstName, lastName, email, timeZoneId);
        subject.changeUserAuthorization(globalRole, active);
        AuthSubject saved = subjects.saveAndFlush(subject);
        if (profileChanged) {
            profiles.replace(
                    saved.getId(),
                    saved.getUsername(),
                    saved.getFirstName(),
                    saved.getLastName(),
                    saved.getEmail(),
                    saved.getTimeZoneId(),
                    saved.getExternalWorkerId());
        }
        if (credentialStatusChanged) {
            credentials.changeStatus(saved.getId(), saved.isActive());
        }
        return saved;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AuthSubject updateCredential(AuthSubject subject, String passwordHash) {
        subject.changePasswordHash(passwordHash);
        AuthSubject saved = subjects.saveAndFlush(subject);
        credentials.replace(saved.getId(), saved.getPasswordHash(), saved.isActive());
        return saved;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AuthSubject updateWorker(
            AuthSubject subject,
            String externalWorkerId,
            String warehouseId,
            String username,
            String passwordHash) {
        subject.reconfigureWorker(externalWorkerId, warehouseId, username, passwordHash);
        AuthSubject saved = subjects.saveAndFlush(subject);
        profiles.replace(
                saved.getId(),
                saved.getUsername(),
                saved.getFirstName(),
                saved.getLastName(),
                saved.getEmail(),
                saved.getTimeZoneId(),
                saved.getExternalWorkerId());
        credentials.replace(saved.getId(), saved.getPasswordHash(), saved.isActive());
        return saved;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AuthSubject touchAuthorization(AuthSubject subject) {
        subject.touch();
        return subjects.saveAndFlush(subject);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AuthSubject disableWorker(AuthSubject subject) {
        subject.disable();
        AuthSubject saved = subjects.saveAndFlush(subject);
        credentials.changeStatus(saved.getId(), false);
        return saved;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AuthSubject prepareWorkerDeletion(AuthSubject subject) {
        if (subject.isActive()) {
            subject.disable();
            credentials.changeStatus(subject.getId(), false);
        } else {
            subject.touch();
        }
        return subjects.saveAndFlush(subject);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<UserWarehouseAccess> replaceAccesses(
            AuthSubject subject, List<WarehouseAccessWrite> requested) {
        accesses.deleteAllInBatch(accesses.findAllByUserIdOrderByWarehouseId(subject.getId()));
        List<UserWarehouseAccess> replacements = requested.stream().map(write -> {
            var access = new UserWarehouseAccess();
            access.define(
                    subject,
                    write.warehouseId(),
                    write.accessLevel(),
                    write.comment(),
                    write.active());
            return access;
        }).toList();
        List<UserWarehouseAccess> saved = accesses.saveAllAndFlush(replacements);
        saved.forEach(access -> notes.replace(access.getId(), access.getComment()));
        return saved;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteWorker(AuthSubject subject) {
        subjects.delete(subject);
        subjects.flush();
    }

    public record WarehouseAccessWrite(
            String warehouseId,
            WarehouseAccessLevel accessLevel,
            String comment,
            boolean active) {}
}
