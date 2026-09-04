package dev.buhanzaz.rwms.maintenance.api;

import static dev.buhanzaz.rwms.maintenance.api.FurnitureEquipmentLinkApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import dev.buhanzaz.rwms.maintenance.security.MaintenanceAuthorizer;
import dev.buhanzaz.rwms.maintenance.service.FurnitureEquipmentLinkReviewService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class FurnitureEquipmentLinkControllerTest {
  private final FurnitureEquipmentLinkReviewService review =
      mock(FurnitureEquipmentLinkReviewService.class);
  private final MaintenanceAuthorizer access = mock(MaintenanceAuthorizer.class);
  private final FurnitureEquipmentLinkController controller =
      new FurnitureEquipmentLinkController(review, access);

  @Test
  void exposesExactReviewedReplayOnlyAfterAdministratorAuthorization() {
    Jwt jwt = mock(Jwt.class);
    UUID warehouseId = UUID.randomUUID();
    UUID nodeId = UUID.randomUUID();
    UUID reviewer = UUID.randomUUID();
    when(access.subjectId(jwt)).thenReturn(reviewer);
    FurnitureEquipmentLinkResponse response = response(warehouseId, nodeId, reviewer);
    when(review.review(
            nodeId,
            2,
            FurnitureEquipmentLinkReviewAction.RETRY,
            reviewer,
            "reviewed"))
        .thenReturn(new FurnitureEquipmentLinkReviewService.ReviewedLink(response, true));

    var result = controller.review(
        jwt,
        nodeId,
        new FurnitureEquipmentLinkReviewRequest(
            2L, FurnitureEquipmentLinkReviewAction.RETRY, "reviewed"));

    verify(access).requireGlobalManage(jwt);
    assertThat(result.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
    assertThat(result.getBody()).isEqualTo(response);
  }

  @Test
  void deniedCallerCannotListOrReviewFurnitureLinks() {
    Jwt jwt = mock(Jwt.class);
    UUID nodeId = UUID.randomUUID();
    doThrow(new AccessDeniedException("denied"))
        .when(access)
        .requireGlobalManage(jwt);

    assertThatThrownBy(() -> controller.list(jwt, 0, 50, null))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> controller.review(
        jwt,
        nodeId,
        new FurnitureEquipmentLinkReviewRequest(
            0L, FurnitureEquipmentLinkReviewAction.ABANDON, "reviewed")))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(review);
  }

  private static FurnitureEquipmentLinkResponse response(
      UUID warehouseId, UUID nodeId, UUID reviewer) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    return new FurnitureEquipmentLinkResponse(
        nodeId,
        warehouseId,
        UUID.randomUUID(),
        1,
        "Chair",
        FurnitureEquipmentLinkState.PENDING,
        null,
        null,
        null,
        null,
        0,
        now,
        null,
        null,
        null,
        3,
        reviewer,
        FurnitureEquipmentLinkReviewAction.RETRY,
        "reviewed",
        now,
        null,
        null,
        now.minusMinutes(1),
        now);
  }
}
