package dev.buhanzaz.rwms.auth.api;

import dev.buhanzaz.rwms.auth.service.UserAdministrationService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrative HTTP API for managing interactive authorization users and their warehouse grants.
 *
 * <p>Business transitions, authorization boundaries, optimistic concurrency, and event creation
 * remain owned by {@link UserAdministrationService}; this controller only adapts HTTP requests.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final UserAdministrationService users;

    /**
     * Returns all administrable interactive users in the service's stable presentation order.
     *
     * @return administrative projections of all interactive users
     */
    @GetMapping
    List<AdminUserResponse> list() {
        return users.listUsers();
    }

    /**
     * Returns the administrative projection of one interactive user.
     *
     * @param id authorization-subject identifier
     * @return requested administrative projection
     */
    @GetMapping("/{id}")
    AdminUserResponse get(@PathVariable UUID id) {
        return users.getUser(id);
    }

    /**
     * Creates an interactive user and returns its administrative projection with a location URI.
     *
     * @param request user-creation command
     * @param authentication authenticated administrator
     * @return {@code 201 Created} response containing the created projection
     */
    @PostMapping
    ResponseEntity<AdminUserResponse> create(
            @Valid @RequestBody CreateUserRequest request,
            Authentication authentication) {
        AdminUserResponse created = users.create(request, authentication);
        return ResponseEntity.created(URI.create("/api/admin/users/" + created.id())).body(created);
    }

    /**
     * Updates an interactive user using the request's optimistic-concurrency version.
     *
     * @param id authorization-subject identifier
     * @param request user-replacement command
     * @param authentication authenticated administrator
     * @return updated administrative projection
     */
    @PutMapping("/{id}")
    AdminUserResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRequest request,
            Authentication authentication) {
        return users.update(id, request, authentication);
    }

    /**
     * Replaces a user's password when the caller supplies the current expected version.
     *
     * @param id authorization-subject identifier
     * @param request write-only password-replacement command
     * @param authentication authenticated administrator
     * @return {@code 204 No Content} when the command is accepted
     */
    @PutMapping("/{id}/password")
    ResponseEntity<Void> resetPassword(
            @PathVariable UUID id,
            @Valid @RequestBody AdminPasswordResetRequest request,
            Authentication authentication) {
        users.resetPassword(id, request.password(), request.expectedVersion(), authentication);
        return ResponseEntity.noContent().build();
    }

    /**
     * Replaces the complete configured warehouse-grant set for a user.
     *
     * @param id authorization-subject identifier
     * @param request complete warehouse-grant replacement command
     * @param authentication authenticated administrator
     * @return updated administrative projection
     */
    @PutMapping("/{id}/warehouse-accesses")
    AdminUserResponse replaceWarehouseAccesses(
            @PathVariable UUID id,
            @Valid @RequestBody WarehouseAccessesUpdateRequest request,
            Authentication authentication) {
        return users.replaceAccesses(id, request.accesses(), request.expectedVersion(), authentication);
    }

    /**
     * Delegates a user-deletion request to the domain service.
     *
     * <p>The service currently fails closed because physical deletion would discard history and
     * active-session guarantees have not been proven.
     *
     * @param id authorization-subject identifier
     * @param authentication authenticated administrator
     * @return no-content response if the service ever accepts the transition
     */
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication) {
        users.delete(id, authentication);
        return ResponseEntity.noContent().build();
    }
}
