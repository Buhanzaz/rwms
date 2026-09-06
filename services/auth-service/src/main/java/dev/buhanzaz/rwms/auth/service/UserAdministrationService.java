package dev.buhanzaz.rwms.auth.service;

import dev.buhanzaz.rwms.auth.api.AdminUserResponse;
import dev.buhanzaz.rwms.auth.api.ActorDisplayResponse;
import dev.buhanzaz.rwms.auth.api.CreateUserRequest;
import dev.buhanzaz.rwms.auth.api.CurrentUserResponse;
import dev.buhanzaz.rwms.auth.api.EffectiveWarehouseAccessDto;
import dev.buhanzaz.rwms.auth.api.UpdateUserRequest;
import dev.buhanzaz.rwms.auth.api.WarehouseAccessDto;
import dev.buhanzaz.rwms.auth.api.WarehouseAccessRequest;
import dev.buhanzaz.rwms.auth.config.OAuthClientProperties;
import dev.buhanzaz.rwms.auth.domain.AuthSubject;
import dev.buhanzaz.rwms.auth.domain.PrincipalType;
import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import dev.buhanzaz.rwms.auth.eventing.AuthAggregateType;
import dev.buhanzaz.rwms.auth.eventing.AuthEventFactFactory;
import dev.buhanzaz.rwms.auth.eventing.AuthEventStore;
import dev.buhanzaz.rwms.auth.eventing.AuthEventTypes;
import dev.buhanzaz.rwms.auth.eventing.AuthInvariantGuard;
import dev.buhanzaz.rwms.auth.eventing.AuthProjectionWriter;
import dev.buhanzaz.rwms.auth.eventing.AuthProjectionWriter.WarehouseAccessWrite;
import dev.buhanzaz.rwms.auth.eventing.AuthSubjectCredentialStore;
import dev.buhanzaz.rwms.auth.eventing.AuthSubjectProfileStore;
import dev.buhanzaz.rwms.auth.eventing.UserWarehouseAccessNoteStore;
import dev.buhanzaz.rwms.auth.integration.warehouse.WarehouseExistenceClient;
import dev.buhanzaz.rwms.auth.mapper.AuthResponseMapper;
import dev.buhanzaz.rwms.auth.repository.AuthSubjectRepository;
import dev.buhanzaz.rwms.auth.repository.UserWarehouseAccessRepository;
import dev.buhanzaz.rwms.auth.repository.UserWarehouseAccessRepository.UserAccessRow;
import dev.buhanzaz.rwms.auth.security.AuthPrincipal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Transactional owner of interactive user administration, warehouse grants, and the corresponding
 * authorization event stream.
 *
 * <p>The service fences mutable commands with the aggregate and stream versions, preserves the
 * last active system administrator, and never physically deletes a user until external audit and
 * session safety can be proven.
 */
@Service
@RequiredArgsConstructor
public class UserAdministrationService {

    private static final int MAX_ACTOR_DISPLAY_SUBJECTS = 100;

    private final AuthSubjectRepository subjects;
    private final UserWarehouseAccessRepository accesses;
    private final PasswordEncoder passwordEncoder;
    private final PrincipalNamePolicy principalNames;
    private final WarehouseExistenceClient warehouseExistenceClient;
    private final WarehouseIdentifierPolicy warehouseIdentifiers;
    private final AuthProjectionWriter projectionWriter;
    private final AuthEventStore eventStore;
    private final AuthEventFactFactory eventFacts;
    private final AuthInvariantGuard invariantGuard;
    private final AuthSubjectCredentialStore credentials;
    private final AuthSubjectProfileStore profiles;
    private final UserWarehouseAccessNoteStore accessNotes;
    private final AuthResponseMapper responseMapper;
    private final AuthorizationRevocationService authorizationRevocations;

    /**
     * Lists all interactive users as administrator-safe representations, ordered by login.
     *
     * @return user representations including current warehouse access grants
     */
    @Transactional(readOnly = true)
    public List<AdminUserResponse> listUsers(Authentication actor) {
        AuthSubject current = currentSubject(actor);
        requireUserAdministrator(current);
        List<AuthSubject> visible = subjects.findAllByPrincipalTypeOrderByUsername(PrincipalType.USER);
        return adminResponses(visible).stream()
                .sorted(java.util.Comparator.comparing(AdminUserResponse::username, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * Gets one interactive user and its warehouse access grants.
     *
     * @param id user subject identifier
     * @return administrative user representation
     */
    @Transactional(readOnly = true)
    public AdminUserResponse getUser(UUID id, Authentication actor) {
        AuthSubject current = currentSubject(actor);
        return adminResponse(visibleUser(id, current));
    }

    /**
     * Resolves the currently authenticated interactive user and computes the effective warehouse
     * access claims exposed to the caller.
     *
     * @param authentication current Spring Security authentication
     * @return current-user representation without credential material
     */
    @Transactional(readOnly = true)
    public CurrentUserResponse currentUser(Authentication authentication) {
        AuthSubject subject = currentSubject(authentication);
        var profile = profiles.require(subject.getId());
        UserGlobalRole role = subject.getGlobalRole();
        boolean accessAll = role == UserGlobalRole.SYSTEM_ADMIN || role == UserGlobalRole.WMS_ADMIN;
        List<EffectiveWarehouseAccessDto> effectiveAccesses = accesses
                .findAllByUserIdAndActiveTrueOrderByWarehouseId(subject.getId()).stream()
                .map(access -> new EffectiveWarehouseAccessDto(
                        access.getWarehouseId(), access.getAccessLevel().effectiveFor(role)))
                .toList();
        return responseMapper.toCurrent(
                subject, profile, displayName(profile), accessAll, effectiveAccesses);
    }

    /**
     * Resolves a bounded, de-duplicated list of author display identities while retaining input
     * order and omitting unknown subject IDs.
     *
     * @param subjectIds requested author subject identifiers
     * @return available display identities in first-occurrence order
     */
    @Transactional(readOnly = true)
    public List<ActorDisplayResponse> actorDisplays(
            List<UUID> subjectIds,
            Authentication authentication) {
        if (subjectIds.size() > MAX_ACTOR_DISPLAY_SUBJECTS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "За один запрос можно получить не более " + MAX_ACTOR_DISPLAY_SUBJECTS + " авторов");
        }
        requireUserAdministrator(currentSubject(authentication));
        var requestedIds = new LinkedHashSet<>(subjectIds);
        Map<UUID, AuthSubject> foundSubjects = subjects.findAllById(requestedIds).stream()
                .collect(java.util.stream.Collectors.toMap(AuthSubject::getId, subject -> subject));
        Map<UUID, AuthSubjectProfileStore.Profile> profilesBySubjectId =
                profiles.findAllBySubjectIds(foundSubjects.keySet());
        return requestedIds.stream()
                .map(foundSubjects::get)
                .filter(Objects::nonNull)
                .map(subject -> responseMapper.toActorDisplay(
                        subject, requiredProfile(profilesBySubjectId, subject.getId())))
                .toList();
    }

    /**
     * Creates an interactive user, its access projection, and initial authorization event in one
     * transaction.
     *
     * @param request requested profile, role, credentials, and warehouse grants
     * @param actor authenticated administrator initiating the command
     * @return created administrator-safe user representation
     */
    @Transactional
    public AdminUserResponse create(CreateUserRequest request, Authentication actor) {
        AuthSubject current = currentSubject(actor);
        requireUserAdministrator(current);
        checkSystemAdminBoundary(current, null, request.globalRole());
        String username = normalizedUsername(request.username());
        ensureUsernameAvailable(username, null);
        List<CanonicalWarehouseAccess> canonicalAccesses = canonicalAccesses(
                request.warehouseAccesses() == null ? List.of() : request.warehouseAccesses());
        validateWarehouses(canonicalAccesses);
        boolean mobileAppAccess = requestedMobileAppAccess(
                request.globalRole(), request.mobileAppAccess(), false);
        boolean rentalAccess = requestedRentalAccess(
                request.rentalAccess(), request.globalRole().hasRentalAccessByDefault());
        AuthSubject subject = projectionWriter.insertUser(
                username,
                passwordEncoder.encode(request.password()),
                request.firstName(),
                request.lastName(),
                request.email(),
                request.timeZoneId(),
                request.globalRole(),
                request.active() == null || request.active(),
                mobileAppAccess,
                rentalAccess);
        projectionWriter.replaceAccesses(subject, accessWrites(canonicalAccesses));
        eventStore.initialize(
                AuthAggregateType.USER_AUTHORIZATION,
                subject.getId(),
                subject.getVersion(),
                AuthEventTypes.USER_CREATED,
                eventFacts.userAuthorization(subject),
                eventFacts.actor(current));
        return adminResponse(subject);
    }

    /**
     * Applies a version-fenced user profile and authorization change, emitting the appropriate
     * event and revoking only the OAuth authorization affected by the removed access.
     *
     * @param id user subject identifier
     * @param request complete requested mutable user state and expected version
     * @param actor authenticated administrator initiating the command
     * @return updated administrator-safe user representation
     */
    @Transactional
    public AdminUserResponse update(UUID id, UpdateUserRequest request, Authentication actor) {
        AuthSubject current = currentSubject(actor);
        AuthSubject subject = visibleUser(id, current);
        checkSystemAdminBoundary(current, subject, request.globalRole());
        checkVersion(subject, request.expectedVersion());
        String username = normalizedUsername(request.username());
        var profile = profiles.require(subject.getId());
        boolean mobileAppAccess = requestedMobileAppAccess(
                request.globalRole(), request.mobileAppAccess(), subject.isMobileAppAccess());
        boolean rentalAccess = requestedRentalAccess(request.rentalAccess(), subject.isRentalAccess());
        boolean profileChanged = !Objects.equals(profile.username(), username)
                || !Objects.equals(profile.firstName(), normalizedOptional(request.firstName()))
                || !Objects.equals(profile.lastName(), normalizedOptional(request.lastName()))
                || !Objects.equals(profile.email(), normalizedOptional(request.email()))
                || !Objects.equals(profile.timeZoneId(), normalizedOptional(request.timeZoneId()));
        boolean credentialStatusChanged = subject.isActive() != request.active();
        boolean authorizationChanged = subject.getGlobalRole() != request.globalRole()
                || subject.isMobileAppAccess() != mobileAppAccess
                || subject.isRentalAccess() != rentalAccess;
        long streamVersion = eventStore.lockCurrentVersion(
                AuthAggregateType.USER_AUTHORIZATION, subject.getId());
        checkStreamVersion(streamVersion, request.expectedVersion());
        if (!profileChanged && !credentialStatusChanged && !authorizationChanged) {
            return adminResponse(subject);
        }
        boolean disabling = subject.isActive() && !request.active();
        boolean revokeManagerAccess =
                (subject.isMobileAppAccess() && !mobileAppAccess) || disabling;
        boolean revokeRentalAccess = subject.isRentalAccess() && !rentalAccess;
        boolean removesSystemAdmin = subject.getGlobalRole() == UserGlobalRole.SYSTEM_ADMIN
                && request.globalRole() != UserGlobalRole.SYSTEM_ADMIN;
        if (subject.getId().equals(current.getId()) && disabling) {
            throw conflict("Нельзя отключить текущего пользователя");
        }
        if (disabling || removesSystemAdmin) {
            invariantGuard.lockSystemAdminInvariant();
            if (isLastActiveSystemAdmin(subject)) {
                throw conflict("Нельзя отключить или понизить последнего активного SYSTEM_ADMIN");
            }
        }
        ensureUsernameAvailable(username, id);
        subject = projectionWriter.updateUser(
                subject,
                username,
                request.firstName(),
                request.lastName(),
                request.email(),
                request.timeZoneId(),
                request.globalRole(),
                request.active(),
                mobileAppAccess,
                rentalAccess,
                profileChanged,
                credentialStatusChanged);
        eventStore.append(
                AuthAggregateType.USER_AUTHORIZATION,
                subject.getId(),
                streamVersion,
                profileChanged && !credentialStatusChanged && !authorizationChanged
                        ? AuthEventTypes.USER_PROFILE_CHANGED
                        : AuthEventTypes.USER_CHANGED,
                eventFacts.userAuthorization(subject),
                eventFacts.actor(current));
        if (revokeRentalAccess) {
            authorizationRevocations.revokePrincipal(profile.username());
        } else if (revokeManagerAccess || !Objects.equals(profile.username(), username)) {
            authorizationRevocations.revokePrincipalClient(
                    profile.username(), OAuthClientProperties.MANAGER_ANDROID_CLIENT_ID);
        }
        return adminResponse(subject);
    }

    /**
     * Changes an interactive user's password under both projection and event-stream version fences,
     * then revokes stored authorizations.
     *
     * @param id user subject identifier
     * @param password replacement password
     * @param expectedVersion caller's current aggregate version
     * @param actor authenticated administrator initiating the command
     */
    @Transactional
    public void resetPassword(
            UUID id,
            String password,
            int expectedVersion,
            Authentication actor) {
        AuthSubject current = currentSubject(actor);
        AuthSubject subject = visibleUser(id, current);
        checkSystemAdminBoundary(current, subject, subject.getGlobalRole());
        checkVersion(subject, expectedVersion);
        long streamVersion = eventStore.lockCurrentVersion(
                AuthAggregateType.USER_AUTHORIZATION, subject.getId());
        checkStreamVersion(streamVersion, expectedVersion);
        if (passwordEncoder.matches(password, credentials.require(subject.getId()).passwordHash())) {
            return;
        }
        subject = projectionWriter.updateCredential(subject, passwordEncoder.encode(password));
        eventStore.append(
                AuthAggregateType.USER_AUTHORIZATION,
                subject.getId(),
                streamVersion,
                AuthEventTypes.USER_PASSWORD_CHANGED,
                eventFacts.userAuthorization(subject),
                eventFacts.actor(current));
        authorizationRevocations.revokePrincipal(
                profiles.require(subject.getId()).username());
    }

    /**
     * Replaces a user's complete warehouse-grant set after canonicalizing identifiers and verifying
     * all referenced warehouses; an equal replacement is an event-free retry.
     *
     * @param id user subject identifier
     * @param requestedAccesses complete replacement grant list
     * @param expectedVersion caller's current aggregate version
     * @param actor authenticated administrator initiating the command
     * @return updated administrator-safe user representation
     */
    @Transactional
    public AdminUserResponse replaceAccesses(
            UUID id,
            List<WarehouseAccessRequest> requestedAccesses,
            int expectedVersion,
            Authentication actor) {
        AuthSubject current = currentSubject(actor);
        AuthSubject subject = visibleUser(id, current);
        checkSystemAdminBoundary(current, subject, subject.getGlobalRole());
        checkVersion(subject, expectedVersion);
        List<CanonicalWarehouseAccess> canonicalAccesses = canonicalAccesses(requestedAccesses);
        long streamVersion = eventStore.lockCurrentVersion(
                AuthAggregateType.USER_AUTHORIZATION, subject.getId());
        checkStreamVersion(streamVersion, expectedVersion);
        if (accessesEqual(subject, canonicalAccesses)) {
            return adminResponse(subject);
        }
        validateWarehouses(canonicalAccesses);
        projectionWriter.replaceAccesses(subject, accessWrites(canonicalAccesses));
        subject = projectionWriter.touchAuthorization(subject);
        eventStore.append(
                AuthAggregateType.USER_AUTHORIZATION,
                subject.getId(),
                streamVersion,
                AuthEventTypes.USER_GRANTS_CHANGED,
                eventFacts.userAuthorization(subject),
                eventFacts.actor(current));
        return adminResponse(subject);
    }

    /**
     * Rejects physical user deletion after applying actor and last-system-administrator safeguards.
     *
     * <p>Disabling the user is the supported reversible operation until external history and active
     * sessions can be proven absent.
     *
     * @param id user subject identifier
     * @param actor authenticated administrator initiating the command
     */
    @Transactional
    public void delete(UUID id, Authentication actor) {
        AuthSubject current = currentSubject(actor);
        AuthSubject subject = visibleUser(id, current);
        if (subject.getId().equals(current.getId())) {
            throw conflict("Нельзя удалить текущего пользователя");
        }
        checkSystemAdminBoundary(current, subject, subject.getGlobalRole());
        invariantGuard.lockSystemAdminInvariant();
        if (isLastActiveSystemAdmin(subject)) {
            throw conflict("Нельзя удалить последнего активного SYSTEM_ADMIN");
        }
        throw conflict("Физическое удаление пользователя запрещено: отсутствие истории и активных сессий не доказано");
    }

    /**
     * Converts each requested warehouse identifier to its UUID form and rejects duplicate logical
     * grants before any mutation starts.
     */
    private List<CanonicalWarehouseAccess> canonicalAccesses(List<WarehouseAccessRequest> requestedAccesses) {
        var canonical = new LinkedHashMap<UUID, CanonicalWarehouseAccess>();
        for (WarehouseAccessRequest request : requestedAccesses) {
            String value = request.warehouseId().trim();
            UUID warehouseId = warehouseIdentifiers.canonicalUuid(value);
            var access = new CanonicalWarehouseAccess(warehouseId, request);
            if (canonical.putIfAbsent(warehouseId, access) != null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Склад в accesses указан повторно: " + warehouseId);
            }
        }
        return List.copyOf(canonical.values());
    }

    /** Verifies every distinct requested warehouse before writing the replacement grant set. */
    private void validateWarehouses(List<CanonicalWarehouseAccess> requestedAccesses) {
        warehouseExistenceClient.requireActive(requestedAccesses.stream()
                .map(CanonicalWarehouseAccess::warehouseId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    /** Maps canonical transport requests to the projection writer's normalized access values. */
    private List<WarehouseAccessWrite> accessWrites(List<CanonicalWarehouseAccess> requestedAccesses) {
        return requestedAccesses.stream()
                .map(canonical -> new WarehouseAccessWrite(
                        canonical.warehouseId().toString(),
                        canonical.request().accessLevel(),
                        normalizedOptional(canonical.request().comment()),
                        canonical.request().active() == null || canonical.request().active()))
                .toList();
    }

    /**
     * Compares the requested complete grant set against the projection and its separately held
     * comments, treating a missing note-vault record as an invariant failure.
     */
    private boolean accessesEqual(
            AuthSubject subject, List<CanonicalWarehouseAccess> requestedAccesses) {
        var current = accesses.findAllByUserIdOrderByWarehouseId(subject.getId());
        var notes = accessNotes.findAllByUserId(subject.getId());
        if (current.size() != requestedAccesses.size()) {
            return false;
        }
        var requestedByWarehouse = requestedAccesses.stream()
                .collect(java.util.stream.Collectors.toMap(CanonicalWarehouseAccess::warehouseId, value -> value));
        return current.stream().allMatch(access -> {
            var requested = requestedByWarehouse.get(UUID.fromString(access.getWarehouseId()));
            return requested != null
                    && access.getAccessLevel() == requested.request().accessLevel()
                    && access.isActive() == (requested.request().active() == null || requested.request().active())
                    && Objects.equals(
                            java.util.Optional.ofNullable(notes.get(access.getId()))
                                    .orElseThrow(() -> new IllegalStateException(
                                            "Warehouse access note vault entry is missing"))
                                    .comment(),
                            normalizedOptional(requested.request().comment()));
        });
    }

    /** Builds the complete interactive-user list from batched profile, grant, and note reads. */
    private List<AdminUserResponse> adminResponses(List<AuthSubject> subjectsToRender) {
        if (subjectsToRender.isEmpty()) {
            return List.of();
        }
        var subjectIds = subjectsToRender.stream()
                .map(AuthSubject::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, AuthSubjectProfileStore.Profile> profilesBySubjectId =
                profiles.findAllBySubjectIds(subjectIds);
        List<UserAccessRow> accessRows = accesses.findAllResponseRowsByPrincipalTypeOrderByUserIdAndWarehouseId(PrincipalType.USER);
        var accessRowsBySubjectId = accessRows.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        UserAccessRow::userId, LinkedHashMap::new, java.util.stream.Collectors.toList()));
        Map<UUID, UserWarehouseAccessNoteStore.Note> notesByAccessId = accessNotes.findAllByAccessIds(
                accessRows.stream().map(UserAccessRow::accessId).collect(java.util.stream.Collectors.toSet()));
        return subjectsToRender.stream()
                .map(subject -> adminResponse(
                        subject,
                        requiredProfile(profilesBySubjectId, subject.getId()),
                        accessRowsBySubjectId.getOrDefault(subject.getId(), List.of()),
                        notesByAccessId))
                .toList();
    }

    private AuthSubjectProfileStore.Profile requiredProfile(
            Map<UUID, AuthSubjectProfileStore.Profile> profilesBySubjectId, UUID subjectId) {
        return java.util.Optional.ofNullable(profilesBySubjectId.get(subjectId))
                .orElseThrow(() -> new IllegalStateException("Auth subject PII vault entry is missing"));
    }

    private AdminUserResponse adminResponse(
            AuthSubject subject,
            AuthSubjectProfileStore.Profile profile,
            List<UserAccessRow> accessRows,
            Map<UUID, UserWarehouseAccessNoteStore.Note> notesByAccessId) {
        List<WarehouseAccessDto> warehouseAccesses = accessRows.stream()
                .map(access -> new WarehouseAccessDto(
                        access.warehouseId(),
                        access.accessLevel(),
                        java.util.Optional.ofNullable(notesByAccessId.get(access.accessId()))
                                .orElseThrow(() -> new IllegalStateException(
                                        "Warehouse access note vault entry is missing"))
                                .comment(),
                        access.active()))
                .toList();
        return responseMapper.toAdmin(subject, profile, warehouseAccesses);
    }

    /** Builds the administrator representation from the authorization projection and note vault. */
    private AdminUserResponse adminResponse(AuthSubject subject) {
        var profile = profiles.require(subject.getId());
        var notes = accessNotes.findAllByUserId(subject.getId());
        List<WarehouseAccessDto> warehouseAccesses = accesses.findAllByUserIdOrderByWarehouseId(subject.getId()).stream()
                .map(access -> new WarehouseAccessDto(
                        access.getWarehouseId(),
                        access.getAccessLevel(),
                        java.util.Optional.ofNullable(notes.get(access.getId()))
                                .orElseThrow(() -> new IllegalStateException("Warehouse access note vault entry is missing"))
                                .comment(),
                        access.isActive()))
                .toList();
        return responseMapper.toAdmin(subject, profile, warehouseAccesses);
    }

    /** Resolves an authenticated principal to an active interactive user subject. */
    private AuthSubject currentSubject(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Требуется аутентификация");
        }
        if (authentication.getPrincipal() instanceof AuthPrincipal principal) {
            return user(principal.id());
        }
        return profiles.findSubjectIdByUsername(authentication.getName())
                .flatMap(subjects::findById)
                .filter(subject -> subject.getPrincipalType() == PrincipalType.USER && subject.isActive())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Пользователь токена не найден"));
    }

    /** Loads an interactive user or maps a missing/non-user subject to a not-found response. */
    private AuthSubject user(UUID id) {
        return subjects.findById(id)
                .filter(subject -> subject.getPrincipalType() == PrincipalType.USER)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
    }

    /** Loads a user only after independently enforcing the administrator role. */
    private AuthSubject visibleUser(UUID id, AuthSubject actor) {
        requireUserAdministrator(actor);
        return user(id);
    }

    /** Keeps administration invariants intact when the service is invoked outside HTTP routing. */
    private void requireUserAdministrator(AuthSubject actor) {
        if (actor.getGlobalRole() != UserGlobalRole.SYSTEM_ADMIN
                && actor.getGlobalRole() != UserGlobalRole.WMS_ADMIN) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Недостаточно прав для управления пользователями");
        }
    }

    /** Determines whether changing this subject would remove the last active system administrator. */
    private boolean isLastActiveSystemAdmin(AuthSubject subject) {
        return subject.isActive()
                && subject.getGlobalRole() == UserGlobalRole.SYSTEM_ADMIN
                && subjects.countByPrincipalTypeAndGlobalRoleAndActiveTrue(
                        PrincipalType.USER, UserGlobalRole.SYSTEM_ADMIN) <= 1;
    }

    /** Fences a command against a stale JPA aggregate version. */
    private void checkVersion(AuthSubject subject, int expectedVersion) {
        if (subject.getVersion() != expectedVersion) {
            throw conflict("Пользователь уже изменён; обновите данные и повторите действие");
        }
    }

    /** Fences a command against a stale authorization event-stream version. */
    private void checkStreamVersion(long streamVersion, int expectedVersion) {
        if (streamVersion != expectedVersion) {
            throw conflict("Поток авторизации уже изменён; обновите данные и повторите действие");
        }
    }

    /** Prevents non-system administrators from creating or administering system-administrator accounts. */
    private void checkSystemAdminBoundary(
            AuthSubject actor,
            AuthSubject target,
            UserGlobalRole requestedRole) {
        if (actor.getGlobalRole() == UserGlobalRole.SYSTEM_ADMIN) {
            return;
        }
        if ((target != null && target.getGlobalRole() == UserGlobalRole.SYSTEM_ADMIN)
                || requestedRole == UserGlobalRole.SYSTEM_ADMIN) {
            throw conflict("Только SYSTEM_ADMIN может управлять учётными записями SYSTEM_ADMIN");
        }
    }

    /** Requires that a login is not reserved for an OAuth client or owned by another subject. */
    private void ensureUsernameAvailable(String username, UUID id) {
        principalNames.requireAvailableForHumanPrincipal(username);
        boolean exists = profiles.findSubjectIdByUsername(username)
                .filter(foundId -> id == null || !foundId.equals(id))
                .isPresent();
        if (exists) {
            throw conflict("Логин уже используется");
        }
    }

    /** Trims the already request-validated required login value. */
    private String normalizedUsername(String username) {
        return username.trim();
    }

    /** Trims an optional profile value and turns blank input into an absent value. */
    private String normalizedOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Resolves optional manager-app access while enforcing the role eligibility invariant. */
    private boolean requestedMobileAppAccess(
            UserGlobalRole role, Boolean requested, boolean current) {
        boolean mobileAppAccess = requested == null ? current : requested;
        if (!role.isManagerAppEligible()) {
            if (Boolean.TRUE.equals(requested)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Доступ к приложению разрешён только системному администратору, "
                                + "администратору WMS или руководителю склада");
            }
            return false;
        }
        return mobileAppAccess;
    }

    /** Resolves an omitted rental-access flag to the current/default value. */
    private boolean requestedRentalAccess(Boolean requested, boolean current) {
        return requested == null ? current : requested;
    }

    /** Creates a display label from a profile name, falling back to the login when no name exists. */
    private String displayName(AuthSubjectProfileStore.Profile profile) {
        String fullName = String.join(" ", Stream.of(profile.firstName(), profile.lastName())
                .filter(value -> value != null && !value.isBlank())
                .toList());
        return fullName.isBlank() ? profile.username() : fullName;
    }

    /** Builds a uniform conflict response for failed user-administration invariants. */
    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    /** Couples a canonical warehouse UUID with the original validated replacement request. */
    private record CanonicalWarehouseAccess(UUID warehouseId, WarehouseAccessRequest request) {
    }
}
