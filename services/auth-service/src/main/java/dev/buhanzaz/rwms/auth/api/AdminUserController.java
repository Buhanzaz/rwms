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

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final UserAdministrationService users;

    @GetMapping
    List<AdminUserResponse> list() {
        return users.listUsers();
    }

    @GetMapping("/{id}")
    AdminUserResponse get(@PathVariable UUID id) {
        return users.getUser(id);
    }

    @PostMapping
    ResponseEntity<AdminUserResponse> create(
            @Valid @RequestBody CreateUserRequest request,
            Authentication authentication) {
        AdminUserResponse created = users.create(request, authentication);
        return ResponseEntity.created(URI.create("/api/admin/users/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    AdminUserResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRequest request,
            Authentication authentication) {
        return users.update(id, request, authentication);
    }

    @PutMapping("/{id}/password")
    ResponseEntity<Void> resetPassword(
            @PathVariable UUID id,
            @Valid @RequestBody AdminPasswordResetRequest request,
            Authentication authentication) {
        users.resetPassword(id, request.password(), request.expectedVersion(), authentication);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/warehouse-accesses")
    AdminUserResponse replaceWarehouseAccesses(
            @PathVariable UUID id,
            @Valid @RequestBody WarehouseAccessesUpdateRequest request,
            Authentication authentication) {
        return users.replaceAccesses(id, request.accesses(), request.expectedVersion(), authentication);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication) {
        users.delete(id, authentication);
        return ResponseEntity.noContent().build();
    }
}
