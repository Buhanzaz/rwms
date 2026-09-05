package dev.buhanzaz.rwms.asset.api;

import dev.buhanzaz.rwms.asset.security.AssetAuthorizer;
import dev.buhanzaz.rwms.asset.service.CabinStatusColorsService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Global read for interactive users and administrator-only version-fenced palette replacement. */
@RestController
@RequestMapping("/api/asset/v1/cabin-settings/status-colors")
public class CabinStatusColorsController {
  private final CabinStatusColorsService service;
  private final AssetAuthorizer access;

  public CabinStatusColorsController(CabinStatusColorsService service, AssetAuthorizer access) {
    this.service = service;
    this.access = access;
  }

  @GetMapping
  public CabinStatusColorsResponse get(@AuthenticationPrincipal Jwt jwt) {
    access.requireGlobalCatalogRead(jwt);
    return service.get();
  }

  @PutMapping
  public CabinStatusColorsResponse replace(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody ReplaceCabinStatusColorsRequest request) {
    access.requireGlobalCatalogManagement(jwt);
    return service.replace(request);
  }
}
