package dev.buhanzaz.rwms.asset.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.asset.security.AssetAuthorizer;
import dev.buhanzaz.rwms.asset.service.AssetInvalidationHub;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AssetEventsControllerTest {
  @Test
  void authorizedWarehouseSubscriptionDisablesProxyBufferingAndResponseCaching() {
    UUID warehouseId = UUID.randomUUID();
    AssetInvalidationHub invalidations = mock(AssetInvalidationHub.class);
    SseEmitter emitter = new SseEmitter();
    when(invalidations.subscribe(warehouseId)).thenReturn(emitter);
    AssetEventsController controller =
        new AssetEventsController(
            invalidations, new AssetAuthorizer(new MockEnvironment(), false));
    MockHttpServletResponse response = new MockHttpServletResponse();

    SseEmitter result =
        controller.events(user(warehouseId, "rwms.read", "VIEW"), warehouseId, null, response);

    assertThat(result).isSameAs(emitter);
    assertThat(response.getHeader("Cache-Control"))
        .isEqualTo("no-cache, no-store, must-revalidate");
    assertThat(response.getHeader("Connection")).isEqualTo("keep-alive");
    assertThat(response.getHeader("X-Accel-Buffering")).isEqualTo("no");
    verify(invalidations).subscribe(warehouseId);
  }

  @Test
  void subscriptionRejectsAnotherWarehouseOrMissingReadScope() {
    UUID permittedWarehouseId = UUID.randomUUID();
    UUID requestedWarehouseId = UUID.randomUUID();
    AssetInvalidationHub invalidations = mock(AssetInvalidationHub.class);
    AssetEventsController controller =
        new AssetEventsController(
            invalidations, new AssetAuthorizer(new MockEnvironment(), false));

    assertThatThrownBy(
            () ->
                controller.events(
                    user(permittedWarehouseId, "rwms.read", "VIEW"),
                    requestedWarehouseId,
                    null,
                    new MockHttpServletResponse()))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                controller.events(
                    user(permittedWarehouseId, "rwms.write", "MANAGE"),
                    permittedWarehouseId,
                    null,
                    new MockHttpServletResponse()))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(invalidations);
  }

  private static Jwt user(UUID warehouseId, String scope, String level) {
    return new Jwt(
        "token",
        Instant.now(),
        Instant.now().plusSeconds(60),
        Map.of("alg", "none"),
        Map.of(
            "sub",
            UUID.randomUUID().toString(),
            "principal_type",
            "USER",
            "scope",
            scope,
            "global_role",
            "VIEWER",
            "warehouse_access",
            List.of(Map.of("warehouseId", warehouseId.toString(), "level", level))));
  }
}
