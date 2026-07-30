package dev.buhanzaz.rwms.auth.service;

import dev.buhanzaz.rwms.auth.api.WorkerCredentialRequest;
import dev.buhanzaz.rwms.auth.api.WorkerCredentialResponse;
import dev.buhanzaz.rwms.auth.api.WorkerCredentialResponse.WorkerCredentialStatus;
import dev.buhanzaz.rwms.auth.domain.AuthSubject;
import dev.buhanzaz.rwms.auth.domain.PrincipalType;
import dev.buhanzaz.rwms.auth.eventing.AuthAggregateType;
import dev.buhanzaz.rwms.auth.eventing.AuthEventFactFactory;
import dev.buhanzaz.rwms.auth.eventing.AuthEventStore;
import dev.buhanzaz.rwms.auth.eventing.AuthEventTypes;
import dev.buhanzaz.rwms.auth.eventing.AuthProjectionWriter;
import dev.buhanzaz.rwms.auth.eventing.AuthSubjectCredentialStore;
import dev.buhanzaz.rwms.auth.eventing.AuthSubjectProfileStore;
import dev.buhanzaz.rwms.auth.mapper.AuthResponseMapper;
import dev.buhanzaz.rwms.auth.repository.AuthSubjectRepository;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class WorkerCredentialService {

    private final AuthSubjectRepository subjects;
    private final PasswordEncoder passwordEncoder;
    private final PrincipalNamePolicy principalNames;
    private final WarehouseIdentifierPolicy warehouseIdentifiers;
    private final AuthSubjectProfileStore profiles;
    private final AuthSubjectCredentialStore credentials;
    private final AuthProjectionWriter projectionWriter;
    private final AuthEventStore eventStore;
    private final AuthEventFactFactory eventFacts;
    private final AuthResponseMapper responseMapper;
    private final AuthorizationRevocationService authorizationRevocations;

    @Transactional
    public WorkerCredentialResponse configure(String workerId, WorkerCredentialRequest request) {
        String normalizedWorkerId = required(workerId, "workerId");
        String normalizedWarehouseId = warehouseIdentifiers
                .canonicalUuid(required(request.warehouseId(), "warehouseId"))
                .toString();
        String normalizedLogin = required(request.appLogin(), "appLogin");
        principalNames.requireAvailableForHumanPrincipal(normalizedLogin);

        AuthSubject subject = profiles.findSubjectIdByExternalWorkerId(normalizedWorkerId)
                .flatMap(subjects::findById)
                .orElse(null);
        profiles.findSubjectIdByUsername(normalizedLogin)
                .filter(found -> subject == null || !found.equals(subject.getId()))
                .ifPresent(found -> {
                    throw conflict("Логин уже используется другой учётной записью");
                });
        if (subject != null && subject.getPrincipalType() != PrincipalType.WORKER) {
            throw conflict("Идентификатор worker связан с пользовательской учётной записью");
        }

        if (subject == null) {
            AuthSubject created = projectionWriter.insertWorker(
                    normalizedWorkerId,
                    normalizedWarehouseId,
                    normalizedLogin,
                    passwordEncoder.encode(request.password()));
            eventStore.initialize(
                    AuthAggregateType.WORKER_ACCESS,
                    created.getId(),
                    created.getVersion(),
                    AuthEventTypes.WORKER_CONFIGURED,
                    eventFacts.workerAccess(created),
                    null);
            return response(created);
        }

        var profile = profiles.require(subject.getId());
        var credential = credentials.require(subject.getId());
        boolean exactRetry = subject.isActive()
                && "ACTIVE".equals(credential.status())
                && Objects.equals(profile.externalWorkerId(), normalizedWorkerId)
                && Objects.equals(profile.username(), normalizedLogin)
                && Objects.equals(subject.getWarehouseId(), normalizedWarehouseId)
                && passwordEncoder.matches(request.password(), credential.passwordHash());
        long streamVersion = lockConsistentStream(subject);
        if (exactRetry) {
            return response(subject);
        }
        AuthSubject updated = projectionWriter.updateWorker(
                subject,
                normalizedWorkerId,
                normalizedWarehouseId,
                normalizedLogin,
                passwordEncoder.encode(request.password()));
        eventStore.append(
                AuthAggregateType.WORKER_ACCESS,
                updated.getId(),
                streamVersion,
                AuthEventTypes.WORKER_CONFIGURED,
                eventFacts.workerAccess(updated),
                null);
        return response(updated);
    }

    @Transactional
    public void resetPassword(String workerId, String password) {
        AuthSubject subject = worker(workerId);
        long streamVersion = lockConsistentStream(subject);
        authorizationRevocations.revokePrincipal(profiles.require(subject.getId()).username());
        if (passwordEncoder.matches(password, credentials.require(subject.getId()).passwordHash())) {
            return;
        }
        AuthSubject updated = projectionWriter.updateCredential(subject, passwordEncoder.encode(password));
        eventStore.append(
                AuthAggregateType.WORKER_ACCESS,
                updated.getId(),
                streamVersion,
                AuthEventTypes.WORKER_PASSWORD_RESET,
                eventFacts.workerAccess(updated),
                null);
    }

    @Transactional
    public void disable(String workerId) {
        AuthSubject subject = findWorker(workerId);
        if (subject == null) {
            return;
        }
        long streamVersion = lockConsistentStream(subject);
        if (!subject.isActive()) {
            if (!"DISABLED".equals(credentials.require(subject.getId()).status())) {
                throw new IllegalStateException("Worker credential vault and authorization projection diverged");
            }
            authorizationRevocations.revokePrincipal(profiles.require(subject.getId()).username());
            return;
        }
        authorizationRevocations.revokePrincipal(profiles.require(subject.getId()).username());
        AuthSubject updated = projectionWriter.disableWorker(subject);
        eventStore.append(
                AuthAggregateType.WORKER_ACCESS,
                updated.getId(),
                streamVersion,
                AuthEventTypes.WORKER_DISABLED,
                eventFacts.workerAccess(updated),
                null);
    }

    @Transactional
    public void enable(String workerId) {
        AuthSubject subject = worker(workerId);
        long streamVersion = lockConsistentStream(subject);
        var credential = credentials.require(subject.getId());
        if (subject.isActive()) {
            if (!"ACTIVE".equals(credential.status())) {
                throw new IllegalStateException(
                        "Worker credential vault and authorization projection diverged");
            }
            return;
        }
        if (!"DISABLED".equals(credential.status())) {
            throw new IllegalStateException(
                    "Worker credential vault and authorization projection diverged");
        }
        AuthSubject updated = projectionWriter.enableWorker(subject);
        eventStore.append(
                AuthAggregateType.WORKER_ACCESS,
                updated.getId(),
                streamVersion,
                AuthEventTypes.WORKER_CONFIGURED,
                eventFacts.workerAccess(updated),
                null);
    }

    @Transactional
    public void delete(String workerId) {
        AuthSubject subject = findWorker(workerId);
        if (subject == null) {
            return;
        }
        long streamVersion = lockConsistentStream(subject);
        authorizationRevocations.revokePrincipal(profiles.require(subject.getId()).username());
        AuthSubject finalProjection = projectionWriter.prepareWorkerDeletion(subject);
        eventStore.append(
                AuthAggregateType.WORKER_ACCESS,
                finalProjection.getId(),
                streamVersion,
                AuthEventTypes.WORKER_DELETED,
                eventFacts.workerAccess(finalProjection),
                null);
        projectionWriter.deleteWorker(finalProjection);
    }

    @Transactional(readOnly = true)
    public WorkerCredentialResponse status(String workerId) {
        return response(worker(workerId));
    }

    private AuthSubject worker(String workerId) {
        AuthSubject subject = findWorker(workerId);
        if (subject == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Учётная запись worker не найдена");
        }
        return subject;
    }

    private AuthSubject findWorker(String workerId) {
        return profiles.findSubjectIdByExternalWorkerId(required(workerId, "workerId"))
                .flatMap(subjects::findById)
                .filter(subject -> subject.getPrincipalType() == PrincipalType.WORKER)
                .orElse(null);
    }

    private long lockConsistentStream(AuthSubject subject) {
        long streamVersion = eventStore.lockCurrentVersion(AuthAggregateType.WORKER_ACCESS, subject.getId());
        if (streamVersion != subject.getVersion()) {
            throw new OptimisticLockingFailureException("Worker access projection is stale");
        }
        return streamVersion;
    }

    private WorkerCredentialResponse response(AuthSubject subject) {
        var profile = profiles.require(subject.getId());
        var credential = credentials.require(subject.getId());
        boolean active = "ACTIVE".equals(credential.status());
        if (active != subject.isActive()) {
            throw new IllegalStateException("Worker credential vault and authorization projection diverged");
        }
        return responseMapper.toWorker(
                profile.externalWorkerId(),
                subject.getWarehouseId(),
                profile.username(),
                active ? WorkerCredentialStatus.ACTIVE : WorkerCredentialStatus.DISABLED);
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " обязателен");
        }
        return value.trim();
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
