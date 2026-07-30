package dev.buhanzaz.rwms.asset.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.buhanzaz.rwms.asset.security.AssetAuthorizer;
import dev.buhanzaz.rwms.asset.service.CabinCompositionService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class CabinSettingsControllerTest {
  private final CabinCompositionService service = mock(CabinCompositionService.class);
  private final CabinSettingsController controller =
      new CabinSettingsController(service, new AssetAuthorizer(new MockEnvironment(), false));

  @Test
  void deletesAnUnreferencedSettingWithItsExpectedVersion() {
    UUID itemId = UUID.randomUUID();

    var response = controller.deleteItem(globalCatalogManager(), itemId, 7);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verify(service).deleteCatalogItem(itemId, 7);
  }

  @Test
  void rejectsDeleteForAUserWithoutGlobalCatalogManagement() {
    assertThatThrownBy(() -> controller.deleteItem(warehouseManager(), UUID.randomUUID(), 0))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Global catalog management");

    verifyNoInteractions(service);
  }

  private static Jwt globalCatalogManager() {
    return user("WMS_ADMIN", "rwms.read rwms.write");
  }

  private static Jwt warehouseManager() {
    return user("WAREHOUSE_MANAGER", "rwms.read rwms.write");
  }

  private static Jwt user(String role, String scope) {
    return new Jwt(
        "token",
        Instant.now(),
        Instant.now().plusSeconds(60),
        Map.of("alg", "none"),
        Map.of(
            "sub", UUID.randomUUID().toString(),
            "principal_type", "USER",
            "scope", scope,
            "global_role", role));
  }
}
