package dev.buhanzaz.rwms.asset.api;

import dev.buhanzaz.rwms.asset.security.AssetAuthorizer;
import dev.buhanzaz.rwms.asset.service.AssetInvalidationHub;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * HTTP adapter for asset events.
 * It exposes the contract boundary without owning a persistence model or domain transition.
 */
@RestController
@RequestMapping("/api/asset/v1")
@RequiredArgsConstructor
public class AssetEventsController {
  private final AssetInvalidationHub invalidations;
  private final AssetAuthorizer access;

  @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam UUID warehouseId,
      @RequestHeader(name = "Last-Event-ID", required = false) String ignoredLastEventId,
      HttpServletResponse response) {
    access.requireRead(jwt, warehouseId);
    response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
    response.setHeader("Connection", "keep-alive");
    response.setHeader("X-Accel-Buffering", "no");
    return invalidations.subscribe(warehouseId);
  }
}
