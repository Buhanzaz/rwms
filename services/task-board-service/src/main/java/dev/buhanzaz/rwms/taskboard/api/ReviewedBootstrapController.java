package dev.buhanzaz.rwms.taskboard.api;

import dev.buhanzaz.rwms.taskboard.api.ApiModels.ReviewedBootstrapResponse;
import dev.buhanzaz.rwms.taskboard.security.AccessLevel;
import dev.buhanzaz.rwms.taskboard.security.WarehouseAccessAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.ReviewedTaskBoardBootstrapService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/warehouses/{warehouseId}")
public class ReviewedBootstrapController {
  private final ReviewedTaskBoardBootstrapService service;
  private final WarehouseAccessAuthorizer access;

  @PostMapping("/reviewed-bootstrap")
  public ReviewedBootstrapResponse bootstrap(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID warehouseId) {
    access.requireUserScope(jwt, "rwms.write");
    access.requireWarehouse(jwt, warehouseId, AccessLevel.MANAGE, false);
    return service.bootstrap(warehouseId);
  }
}
