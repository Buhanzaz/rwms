package dev.buhanzaz.rwms.inventory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.inventory.api.InventoryApiModels.InventoryActorView;
import dev.buhanzaz.rwms.inventory.api.InventoryApiModels.SessionView;
import dev.buhanzaz.rwms.inventory.domain.FurnitureReconciliationState;
import dev.buhanzaz.rwms.inventory.domain.InventoryReviewStage;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import dev.buhanzaz.rwms.inventory.service.InventoryApplicationService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.context.request.ServletWebRequest;
import tools.jackson.databind.json.JsonMapper;

class InventoryControllerConditionalGetTest {
  private final InventoryApplicationService inventory = mock(InventoryApplicationService.class);
  private final UUID warehouseId =
      UUID.fromString("00000000-0000-0000-0000-000000000701");
  private final Jwt jwt =
      Jwt.withTokenValue("inventory-conditional-get-token")
          .header("alg", "none")
          .subject("00000000-0000-0000-0000-000000000702")
          .build();

  private InventoryController controller;

  @BeforeEach
  void setUp() {
    reset(inventory);
    controller =
        new InventoryController(inventory, JsonMapper.builder().findAndAddModules().build());
  }

  @Test
  void activeSessionReturnsEtagAndHonorsWeakListedIfNoneMatch() {
    when(inventory.active(jwt, warehouseId)).thenReturn(Optional.of(session(2, 4)));

    ResponseEntity<SessionView> initial = active(null);

    assertThat(initial.getStatusCode()).isEqualTo(HttpStatus.OK);
    String eTag = initial.getHeaders().getETag();
    assertThat(eTag).matches("^\"[0-9a-f]{64}\"$");
    assertThat(initial.getBody()).isNotNull();

    ResponseEntity<SessionView> notModified = active("\"obsolete\", W/" + eTag);

    assertThat(notModified.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    assertThat(notModified.getHeaders().getETag()).isEqualTo(eTag);
    assertThat(notModified.getBody()).isNull();
  }

  @Test
  void noActiveSessionAlsoReturnsAndHonorsAnEtag() {
    when(inventory.active(jwt, warehouseId)).thenReturn(Optional.empty());

    ResponseEntity<SessionView> initial = active(null);

    assertThat(initial.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    String eTag = initial.getHeaders().getETag();
    assertThat(eTag).matches("^\"[0-9a-f]{64}\"$");

    ResponseEntity<SessionView> notModified = active(eTag);

    assertThat(notModified.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    assertThat(notModified.getHeaders().getETag()).isEqualTo(eTag);
  }

  @Test
  void etagChangesWhenTheActiveSessionRepresentationChanges() {
    when(inventory.active(jwt, warehouseId))
        .thenReturn(Optional.of(session(2, 4)))
        .thenReturn(Optional.of(session(2, 5)));

    String original = active(null).getHeaders().getETag();
    String changed = active(null).getHeaders().getETag();

    assertThat(changed).isNotEqualTo(original);
  }

  private ResponseEntity<SessionView> active(String ifNoneMatch) {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/inventory/v1/sessions/active");
    if (ifNoneMatch != null) {
      request.addHeader(HttpHeaders.IF_NONE_MATCH, ifNoneMatch);
    }
    return controller.active(
        jwt, warehouseId, new ServletWebRequest(request, new MockHttpServletResponse()));
  }

  private SessionView session(long revision, int expectedCount) {
    return new SessionView(
        UUID.fromString("00000000-0000-0000-0000-000000000703"),
        revision,
        warehouseId,
        5,
        "Europe/Moscow",
        new InventoryActorView(
            UUID.fromString("00000000-0000-0000-0000-000000000704"), "Inventory operator"),
        LocalDate.of(2026, 7, 30),
        SessionLifecycle.ACTIVE,
        InventoryReviewStage.CABINS,
        FurnitureReconciliationState.NOT_REQUIRED,
        expectedCount,
        3,
        2,
        OffsetDateTime.of(2026, 7, 30, 12, 0, 0, 0, ZoneOffset.UTC),
        null,
        "NOT_REQUESTED",
        List.of(),
        null,
        null);
  }
}
