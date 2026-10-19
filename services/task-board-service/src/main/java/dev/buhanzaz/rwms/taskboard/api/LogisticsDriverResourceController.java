package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.LogisticsDriverAssignmentApiModels.ContractorDriverResponse;
import static dev.buhanzaz.rwms.taskboard.api.LogisticsDriverAssignmentApiModels.CreateContractorDriverRequest;
import static dev.buhanzaz.rwms.taskboard.api.LogisticsDriverAssignmentApiModels.UpdateContractorDriverRequest;

import dev.buhanzaz.rwms.taskboard.security.AccessLevel;
import dev.buhanzaz.rwms.taskboard.security.WarehouseAccessAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.ContractorDriverService;
import dev.buhanzaz.rwms.taskboard.service.LogisticsDriverDirectoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public warehouse-scoped logistics resource directory for authenticated panel operators.
 *
 * <p>The controller exposes the same task-board-owned availability projection used by the private
 * logistics boundary. It never exposes credentials or ordinary group membership, and contractor
 * creation does not provision DriverApp access.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/warehouses/{warehouseId}/logistics-drivers")
public class LogisticsDriverResourceController {
  private final LogisticsDriverDirectoryService directory;
  private final ContractorDriverService contractors;
  private final WarehouseAccessAuthorizer access;

  /** Lists drivers available at the requested instant for a warehouse-visible logistics form. */
  @GetMapping
  public List<LogisticsDriverIdentityResponse> list(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @RequestParam(required = false) OffsetDateTime at,
      @RequestParam(defaultValue = "false") boolean includeIncoming) {
    access.requireUserScope(jwt, "rwms.read");
    access.requireWarehouse(jwt, warehouseId, AccessLevel.VIEW, false);
    return directory.list(warehouseId, at, includeIncoming);
  }

  /** Creates an on-demand contractor driver under the operator's logistics edit grant. */
  @PostMapping("/contractors")
  @ResponseStatus(HttpStatus.CREATED)
  public ContractorDriverResponse createContractor(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @Valid @RequestBody CreateContractorDriverRequest request) {
    access.requireUserScope(jwt, "rwms.write");
    access.requireWarehouse(jwt, warehouseId, AccessLevel.EDIT, false);
    return contractors.create(warehouseId, request);
  }

  /** Lists every contractor profile owned by the warehouse, including inactive ones. */
  @GetMapping("/contractors")
  public List<ContractorDriverResponse> listContractors(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID warehouseId) {
    access.requireUserScope(jwt, "rwms.read");
    access.requireWarehouse(jwt, warehouseId, AccessLevel.VIEW, false);
    return contractors.list(warehouseId);
  }

  /** Replaces an owned contractor profile under its observed worker version. */
  @PatchMapping("/contractors/{workerId}")
  public ContractorDriverResponse updateContractor(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID workerId,
      @Valid @RequestBody UpdateContractorDriverRequest request) {
    access.requireUserScope(jwt, "rwms.write");
    access.requireWarehouse(jwt, warehouseId, AccessLevel.EDIT, false);
    return contractors.update(warehouseId, workerId, request);
  }

  /** Deletes one unused contractor profile under the observed version fence. */
  @DeleteMapping("/contractors/{workerId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteContractor(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID workerId,
      @RequestParam @Min(0) long expectedVersion) {
    access.requireUserScope(jwt, "rwms.write");
    access.requireWarehouse(jwt, warehouseId, AccessLevel.EDIT, false);
    contractors.delete(warehouseId, workerId, expectedVersion);
  }
}
