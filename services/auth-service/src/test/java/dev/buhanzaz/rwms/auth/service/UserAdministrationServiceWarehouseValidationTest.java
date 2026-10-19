package dev.buhanzaz.rwms.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.auth.api.CreateUserRequest;
import dev.buhanzaz.rwms.auth.api.WarehouseAccessRequest;
import dev.buhanzaz.rwms.auth.domain.AuthSubject;
import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import dev.buhanzaz.rwms.auth.domain.WarehouseAccessLevel;
import dev.buhanzaz.rwms.auth.eventing.AuthEventFactFactory;
import dev.buhanzaz.rwms.auth.eventing.AuthEventStore;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class UserAdministrationServiceWarehouseValidationTest {

    private static final UUID SPB = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MSK = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ADMIN_ID = UUID.fromString("10000000-0000-0000-0000-000000000081");
    private static final UUID NEW_USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000082");

    @Mock
    AuthSubjectRepository subjects;

    @Mock
    UserWarehouseAccessRepository accesses;

    @Mock
    PasswordEncoder passwordEncoder;

    @Mock
    PrincipalNamePolicy principalNames;

    @Mock
    WarehouseExistenceClient warehouseExistenceClient;

    @Mock
    AuthProjectionWriter projectionWriter;

    @Mock
    AuthEventStore eventStore;

    @Mock
    AuthEventFactFactory eventFacts;

    @Mock
    AuthInvariantGuard invariantGuard;

    @Mock
    AuthSubjectCredentialStore credentials;

    @Mock
    AuthSubjectProfileStore profiles;

    @Mock
    UserWarehouseAccessNoteStore accessNotes;

    @Mock
    AuthResponseMapper responseMapper;

    @Mock
    AuthorizationRevocationService authorizationRevocations;

    private UserAdministrationService service;
    private final UsernamePasswordAuthenticationToken actor =
            UsernamePasswordAuthenticationToken.authenticated("admin", "n/a", List.of());

    @BeforeEach
    void setUp() {
        service = new UserAdministrationService(
                subjects,
                accesses,
                passwordEncoder,
                principalNames,
                warehouseExistenceClient,
                new WarehouseIdentifierPolicy(),
                projectionWriter,
                eventStore,
                eventFacts,
                invariantGuard,
                credentials,
                profiles,
                accessNotes,
                responseMapper,
                authorizationRevocations);
        AuthSubject admin = subject("admin", 0, UserGlobalRole.WMS_ADMIN);
        ReflectionTestUtils.setField(admin, "id", ADMIN_ID);
        when(profiles.findSubjectIdByUsername(any())).thenAnswer(invocation ->
                "admin".equalsIgnoreCase(invocation.getArgument(0)) ? Optional.of(ADMIN_ID) : Optional.empty());
        when(subjects.findById(ADMIN_ID)).thenReturn(Optional.of(admin));
        lenient()
                .when(projectionWriter.insertUser(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        anyBoolean(),
                        anyBoolean(),
                        anyBoolean()))
                .thenAnswer(invocation -> {
                    var created = new AuthSubject();
                    created.registerUser(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2),
                            invocation.getArgument(3),
                            invocation.getArgument(4),
                            invocation.getArgument(5),
                            invocation.getArgument(6),
                            invocation.getArgument(7),
                            invocation.getArgument(8),
                            invocation.getArgument(9));
                    ReflectionTestUtils.setField(created, "id", NEW_USER_ID);
                    return created;
                });
        lenient().when(profiles.require(any())).thenAnswer(invocation -> {
            UUID subjectId = invocation.getArgument(0);
            String username = ADMIN_ID.equals(subjectId) ? "admin" : "new.user";
            return new AuthSubjectProfileStore.Profile(
                    subjectId, username, null, null, null, null, null, UUID.randomUUID(), "fixture-hash");
        });
        lenient().when(accessNotes.findAllByUserId(any())).thenReturn(Map.of());
        lenient().when(eventStore.lockCurrentVersion(any(), any())).thenReturn(7L);
        lenient().when(projectionWriter.touchAuthorization(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(accesses.findAllByUserIdOrderByWarehouseId(any())).thenReturn(List.of());
        lenient().when(passwordEncoder.encode(any())).thenReturn("{noop}encoded");
    }

    @Test
    void knownAliasesAndCanonicalIdsConvergeBeforePersistence() {
        service.create(request(List.of(access(" SPB "), access(MSK.toString()))), actor);

        verify(warehouseExistenceClient)
                .requireActive(Set.of(SPB, MSK));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WarehouseAccessWrite>> replacements = ArgumentCaptor.forClass(List.class);
        verify(projectionWriter).replaceAccesses(any(AuthSubject.class), replacements.capture());
        assertThat(replacements.getValue())
                .extracting(WarehouseAccessWrite::warehouseId)
                .containsExactly(SPB.toString(), MSK.toString());
    }

    @Test
    void aliasAndItsCanonicalUuidAreRejectedAsDuplicateBeforeNetworkOrPersistence() {
        assertThatThrownBy(() -> service.create(
                        request(List.of(access("spb"), access(SPB.toString()))), actor))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verifyNoInteractions(warehouseExistenceClient);
        verify(projectionWriter, never())
                .insertUser(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        anyBoolean(),
                        anyBoolean(),
                        anyBoolean());
        verify(accesses, never()).deleteAllInBatch(any());
    }

    @Test
    void failedBatchValidationCannotPartiallyCreateUser() {
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "second warehouse unavailable"))
                .when(warehouseExistenceClient)
                .requireActive(Set.of(SPB, MSK));

        assertThatThrownBy(() -> service.create(
                        request(List.of(access(SPB.toString()), access(MSK.toString()))), actor))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        verify(projectionWriter, never())
                .insertUser(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        anyBoolean(),
                        anyBoolean(),
                        anyBoolean());
        verify(projectionWriter, never()).replaceAccesses(any(), any());
        verify(accesses, never()).deleteAllInBatch(any());
        verify(accesses, never()).saveAllAndFlush(any());
    }

    @Test
    void failedBatchValidationCannotDeleteOrTouchExistingAccesses() {
        UUID userId = UUID.randomUUID();
        AuthSubject user = subject("existing.user", 7, UserGlobalRole.VIEWER);
        ReflectionTestUtils.setField(user, "id", userId);
        when(subjects.findById(userId)).thenReturn(Optional.of(user));
        doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "invalid upstream response"))
                .when(warehouseExistenceClient)
                .requireActive(Set.of(SPB, MSK));

        assertThatThrownBy(() -> service.replaceAccesses(
                        userId,
                        List.of(access(SPB.toString()), access(MSK.toString())),
                        7,
                        actor))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));

        verify(accesses, never()).deleteAllInBatch(any());
        verify(accesses, never()).saveAllAndFlush(any());
        verify(projectionWriter, never()).replaceAccesses(any(), any());
        verify(projectionWriter, never()).touchAuthorization(user);
    }

    @Test
    void unmappedNonUuidIsRejectedBeforeNetwork() {
        assertThatThrownBy(() -> service.create(request(List.of(access("warehouse-unknown"))), actor))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verifyNoInteractions(warehouseExistenceClient);
    }

    private CreateUserRequest request(List<WarehouseAccessRequest> requestedAccesses) {
        return new CreateUserRequest(
                "new.user",
                "new-user-password",
                null,
                null,
                null,
                null,
                UserGlobalRole.VIEWER,
                true,
                null,
                null,
                requestedAccesses);
    }

    private WarehouseAccessRequest access(String warehouseId) {
        return new WarehouseAccessRequest(warehouseId, WarehouseAccessLevel.MANAGE, null, true);
    }

    private AuthSubject subject(String username, int version) {
        return subject(username, version, UserGlobalRole.SYSTEM_ADMIN);
    }

    private AuthSubject subject(String username, int version, UserGlobalRole globalRole) {
        var subject = new AuthSubject();
        subject.registerUser(
                username,
                "{noop}password",
                null,
                null,
                null,
                null,
                globalRole,
                true,
                false,
                globalRole.hasRentalAccessByDefault());
        ReflectionTestUtils.setField(subject, "version", version);
        return subject;
    }
}
