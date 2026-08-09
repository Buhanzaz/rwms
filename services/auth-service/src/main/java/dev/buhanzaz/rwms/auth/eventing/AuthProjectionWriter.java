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

/**
 * Applies auth-domain transitions to the live projection and its private companion vaults.
 *
 * <p>The writer persists the JPA aggregate and synchronizes the profile, credential, and access
 * note stores in the caller's transaction. It does not publish Kafka messages; the owning
 * application service appends an authoritative event through {@link AuthEventStore} afterward.
 */
@Service
@RequiredArgsConstructor
public class AuthProjectionWriter {

    private final AuthSubjectRepository subjects;
    private final UserWarehouseAccessRepository accesses;
    private final AuthSubjectProfileStore profiles;
    private final AuthSubjectCredentialStore credentials;
    private final UserWarehouseAccessNoteStore notes;

    /**
     * Creates a user with default mobile and rental-access policy derived from its role.
     *
     * @param username login identifier
     * @param passwordHash encoded credential representation
     * @param firstName private profile attribute
     * @param lastName private profile attribute
     * @param email private profile attribute
     * @param timeZoneId private profile attribute
     * @param globalRole initial authorization role
     * @param active initial account state
     * @return persisted user aggregate
     */
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
        return insertUser(
                username,
                passwordHash,
                firstName,
                lastName,
                email,
                timeZoneId,
                globalRole,
                active,
                false,
                globalRole.hasRentalAccessByDefault());
    }

    /**
     * Creates a user with explicit mobile access and role-derived rental access.
     *
     * @param username login identifier
     * @param passwordHash encoded credential representation
     * @param firstName private profile attribute
     * @param lastName private profile attribute
     * @param email private profile attribute
     * @param timeZoneId private profile attribute
     * @param globalRole initial authorization role
     * @param active initial account state
     * @param mobileAppAccess whether manager mobile access is requested
     * @return persisted user aggregate
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AuthSubject insertUser(
            String username,
            String passwordHash,
            String firstName,
            String lastName,
            String email,
            String timeZoneId,
            UserGlobalRole globalRole,
            boolean active,
            boolean mobileAppAccess) {
        return insertUser(
                username,
                passwordHash,
                firstName,
                lastName,
                email,
                timeZoneId,
                globalRole,
                active,
                mobileAppAccess,
                globalRole.hasRentalAccessByDefault());
    }

    /**
     * Creates a user with all supported authorization flags explicit.
     *
     * <p>The domain aggregate validates which combinations are legal before the writer persists
     * it and its private vault entries.
     *
     * @param username login identifier
     * @param passwordHash encoded credential representation
     * @param firstName private profile attribute
     * @param lastName private profile attribute
     * @param email private profile attribute
     * @param timeZoneId private profile attribute
     * @param globalRole initial authorization role
     * @param active initial account state
     * @param mobileAppAccess whether manager mobile access is requested
     * @param rentalAccess whether rental functionality is granted
     * @return persisted user aggregate
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AuthSubject insertUser(
            String username,
            String passwordHash,
            String firstName,
            String lastName,
            String email,
            String timeZoneId,
            UserGlobalRole globalRole,
            boolean active,
            boolean mobileAppAccess,
            boolean rentalAccess) {
        var subject = new AuthSubject();
        subject.registerUser(
                username,
                passwordHash,
                firstName,
                lastName,
                email,
                timeZoneId,
                globalRole,
                active,
                mobileAppAccess,
                rentalAccess);
        return insert(subject);
    }

    /**
     * Creates a worker auth subject and initializes its private vault entries.
     *
     * @param externalWorkerId external worker linkage identifier
     * @param warehouseId worker warehouse identifier
     * @param username login identifier
     * @param passwordHash encoded credential representation
     * @return persisted worker aggregate
     */
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

    /**
     * Updates a user while retaining eligible mobile access and current rental access by default.
     *
     * @param subject managed user aggregate
     * @param username replacement login identifier
     * @param firstName replacement private profile attribute
     * @param lastName replacement private profile attribute
     * @param email replacement private profile attribute
     * @param timeZoneId replacement private profile attribute
     * @param globalRole replacement authorization role
     * @param active replacement account state
     * @param profileChanged whether the profile vault must be synchronized
     * @param credentialStatusChanged whether credential availability must be synchronized
     * @return persisted user aggregate
     */
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
        return updateUser(
                subject,
                username,
                firstName,
                lastName,
                email,
                timeZoneId,
                globalRole,
                active,
                subject.isMobileAppAccess()
                        && globalRole != null
                        && globalRole.isManagerAppEligible(),
                subject.isRentalAccess(),
                profileChanged,
                credentialStatusChanged);
    }

    /**
     * Updates a user with explicit mobile access while retaining current rental access.
     *
     * @param subject managed user aggregate
     * @param username replacement login identifier
     * @param firstName replacement private profile attribute
     * @param lastName replacement private profile attribute
     * @param email replacement private profile attribute
     * @param timeZoneId replacement private profile attribute
     * @param globalRole replacement authorization role
     * @param active replacement account state
     * @param mobileAppAccess requested manager mobile access
     * @param profileChanged whether the profile vault must be synchronized
     * @param credentialStatusChanged whether credential availability must be synchronized
     * @return persisted user aggregate
     */
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
            boolean mobileAppAccess,
            boolean profileChanged,
            boolean credentialStatusChanged) {
        return updateUser(
                subject,
                username,
                firstName,
                lastName,
                email,
                timeZoneId,
                globalRole,
                active,
                mobileAppAccess,
                subject.isRentalAccess(),
                profileChanged,
                credentialStatusChanged);
    }

    /**
     * Updates a user projection and synchronizes only the private stores named by change flags.
     *
     * @param subject managed user aggregate
     * @param username replacement login identifier
     * @param firstName replacement private profile attribute
     * @param lastName replacement private profile attribute
     * @param email replacement private profile attribute
     * @param timeZoneId replacement private profile attribute
     * @param globalRole replacement authorization role
     * @param active replacement account state
     * @param mobileAppAccess requested manager mobile access
     * @param rentalAccess requested rental access
     * @param profileChanged whether the profile vault must be synchronized
     * @param credentialStatusChanged whether credential availability must be synchronized
     * @return persisted user aggregate
     */
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
            boolean mobileAppAccess,
            boolean rentalAccess,
            boolean profileChanged,
            boolean credentialStatusChanged) {
        subject.changeUserProfile(username, firstName, lastName, email, timeZoneId);
        subject.changeUserAuthorization(globalRole, active, mobileAppAccess, rentalAccess);
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

    /**
     * Replaces a subject's credential representation and synchronizes the private credential vault.
     *
     * @param subject managed auth aggregate
     * @param passwordHash encoded credential representation
     * @return persisted aggregate
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AuthSubject updateCredential(AuthSubject subject, String passwordHash) {
        subject.changePasswordHash(passwordHash);
        AuthSubject saved = subjects.saveAndFlush(subject);
        credentials.replace(saved.getId(), saved.getPasswordHash(), saved.isActive());
        return saved;
    }

    /**
     * Reconfigures a worker and synchronizes its profile and credential vault entries.
     *
     * @param subject managed worker aggregate
     * @param externalWorkerId replacement external worker linkage
     * @param warehouseId replacement warehouse identifier
     * @param username replacement login identifier
     * @param passwordHash replacement encoded credential representation
     * @return persisted worker aggregate
     */
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

    /**
     * Touches a user authorization aggregate without changing its public authorization fields.
     *
     * @param subject managed auth aggregate
     * @return persisted aggregate with its version/update marker advanced by the domain model
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AuthSubject touchAuthorization(AuthSubject subject) {
        subject.touch();
        return subjects.saveAndFlush(subject);
    }

    /**
     * Disables a worker and mirrors that state into the private credential vault.
     *
     * @param subject managed worker aggregate
     * @return persisted disabled worker aggregate
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AuthSubject disableWorker(AuthSubject subject) {
        subject.disable();
        AuthSubject saved = subjects.saveAndFlush(subject);
        credentials.changeStatus(saved.getId(), false);
        return saved;
    }

    /**
     * Enables a worker and mirrors the usable state into the private credential vault.
     *
     * @param subject managed worker aggregate
     * @return persisted enabled worker aggregate
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AuthSubject enableWorker(AuthSubject subject) {
        subject.enableWorkerAccess();
        AuthSubject saved = subjects.saveAndFlush(subject);
        credentials.changeStatus(saved.getId(), true);
        return saved;
    }

    /**
     * Places a worker in its terminal disabled state before the owning flow appends a delete fact.
     *
     * @param subject managed worker aggregate
     * @return persisted worker prepared for deletion
     */
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

    /**
     * Replaces every user warehouse-access grant and synchronizes its private note vault entries.
     *
     * @param subject managed user aggregate
     * @param requested complete replacement grant set
     * @return persisted replacement grants
     */
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

    /**
     * Deletes a worker's live projection after its terminal authorization event is prepared.
     *
     * @param subject managed worker aggregate to remove
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteWorker(AuthSubject subject) {
        subjects.delete(subject);
        subjects.flush();
    }

    /**
     * Requested state for one warehouse-access grant in a full replacement operation.
     *
     * @param warehouseId warehouse identifier
     * @param accessLevel requested access level
     * @param comment private note kept outside event payloads
     * @param active whether the grant is usable
     */
    public record WarehouseAccessWrite(
            String warehouseId,
            WarehouseAccessLevel accessLevel,
            String comment,
            boolean active) {}
}
