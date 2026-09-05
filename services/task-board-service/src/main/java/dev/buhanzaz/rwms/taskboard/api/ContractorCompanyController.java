package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.ContractorCompanyApiModels.*;

import dev.buhanzaz.rwms.taskboard.security.AccessLevel;
import dev.buhanzaz.rwms.taskboard.security.WarehouseAccessAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.ContractorCompanyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Public company catalog guarded by the same city-level grants as contractor driver profiles. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/warehouses/{warehouseId}/logistics-drivers/companies")
public class ContractorCompanyController {
  private final ContractorCompanyService companies;
  private final WarehouseAccessAuthorizer access;

  @GetMapping
  public List<ContractorCompanyResponse> list(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID warehouseId) {
    access.requireUserScope(jwt, "rwms.read");
    access.requireWarehouse(jwt, warehouseId, AccessLevel.VIEW, false);
    return companies.list(warehouseId);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ContractorCompanyResponse create(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @Valid @RequestBody CreateContractorCompanyRequest request) {
    requireWrite(jwt, warehouseId);
    return companies.create(warehouseId, request);
  }

  @PatchMapping("/{companyId}")
  public ContractorCompanyResponse update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID companyId,
      @Valid @RequestBody UpdateContractorCompanyRequest request) {
    requireWrite(jwt, warehouseId);
    return companies.update(warehouseId, companyId, request);
  }

  @DeleteMapping("/{companyId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID companyId,
      @RequestParam @Min(0) long expectedVersion) {
    requireWrite(jwt, warehouseId);
    companies.delete(warehouseId, companyId, expectedVersion);
  }

  private void requireWrite(Jwt jwt, UUID warehouseId) {
    access.requireUserScope(jwt, "rwms.write");
    access.requireWarehouse(jwt, warehouseId, AccessLevel.EDIT, false);
  }
}
