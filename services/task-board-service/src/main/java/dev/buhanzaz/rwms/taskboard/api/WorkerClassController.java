package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;

import dev.buhanzaz.rwms.taskboard.security.WarehouseAccessAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.RegistryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Public global registry for worker qualifications.
 *
 * <p>Classes describe capability, while workers and groups are warehouse-scoped. Keeping the
 * qualification vocabulary global makes queue bindings and cross-warehouse reporting comparable.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/worker-classes")
@Validated
public class WorkerClassController {
  private final RegistryService service;
  private final WarehouseAccessAuthorizer access;

  /** Lists the global qualification catalog. */
  @GetMapping
  public List<WorkerClassDto> list(@AuthenticationPrincipal Jwt jwt) {
    access.requireUserScope(jwt, "rwms.read");
    return service.listClasses();
  }

  /** Creates a global worker-class definition. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public WorkerClassDto create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody WorkerClassRequest request) {
    write(jwt);
    return service.createClass(request);
  }

  /** Replaces a version-fenced worker-class definition. */
  @PutMapping("/{id}")
  public WorkerClassDto update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody WorkerClassRequest request) {
    write(jwt);
    return service.updateClass(id, request);
  }

  /** Deletes an unused worker class; referenced classes must be deactivated instead. */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam @Min(0) long expectedVersion) {
    write(jwt);
    service.deleteClass(id, expectedVersion);
  }

  private void write(Jwt jwt) {
    access.requireUserScope(jwt, "rwms.write");
    access.requireGlobalManagement(jwt);
  }
}
