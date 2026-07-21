package dev.buhanzaz.rwms.asset;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.asset.eventing.AssetReplayVerifier;
import dev.buhanzaz.rwms.asset.service.AssetService;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.asset.warehouse-registry.enabled=false",
      "spring.cloud.function.definition=",
      "spring.task.scheduling.enabled=false"
    })
@ActiveProfiles("test")
class OldPanelWarehouseDataReplayIntegrationTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired AssetReplayVerifier replay;
  @Autowired AssetService service;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://issuer.invalid");
    registry.add("rwms.cors.allowed-origins", () -> "http://localhost:5173");
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }

  @Test
  void transferredWarehouseDataHasCanonicalReplayParityAndRussianEquipmentNames() {
    AssetReplayVerifier.ReplayParityResult first = replay.rebuildAndVerify();
    AssetReplayVerifier.ReplayParityResult repeated = replay.rebuildAndVerify();

    assertThat(first.aggregateCount()).isEqualTo(464);
    assertThat(first.canonicalChecksum()).matches("[0-9a-f]{64}");
    assertThat(repeated).isEqualTo(first);

    var firstCabin = service.rentalItem(
        UUID.fromString("51000000-0000-4000-8000-000000000001"));
    assertThat(firstCabin.number()).isEqualTo("БЫТ-001");
    assertThat(firstCabin.contents())
        .extracting(content -> content.equipmentCode() + ":" + content.equipmentName())
        .containsExactly("CHAIR:Стул", "TABLE:Стол", "WARDROBE:Шкаф");
  }
}
