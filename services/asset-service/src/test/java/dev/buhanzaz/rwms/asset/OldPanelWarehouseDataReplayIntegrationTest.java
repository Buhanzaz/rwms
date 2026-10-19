package dev.buhanzaz.rwms.asset;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.asset.eventing.AssetReplayVerifier;
import dev.buhanzaz.rwms.asset.service.AssetService;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
  @Autowired JdbcTemplate jdbc;

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
  void transferredWarehouseDataReplaysToCanonicalEquipmentAfterNormalization() {
    AssetReplayVerifier.ReplayParityResult first = replay.rebuildAndVerify();
    AssetReplayVerifier.ReplayParityResult repeated = replay.rebuildAndVerify();

    assertThat(first.aggregateCount()).isPositive();
    assertThat(first.canonicalChecksum()).matches("[0-9a-f]{64}");
    assertThat(repeated).isEqualTo(first);

    assertThat(jdbc.queryForList("""
        select id::text
        from equipment_catalog_item
        where active
        order by id
        """, String.class))
        .containsExactly(
            "52140000-0000-4000-8000-000000000001",
            "52140000-0000-4000-8000-000000000002",
            "52140000-0000-4000-8000-000000000003",
            "52140000-0000-4000-8000-000000000004",
            "52140000-0000-4000-8000-000000000005",
            "52140000-0000-4000-8000-000000000006",
            "52140000-0000-4000-8000-000000000007",
            "52140000-0000-4000-8000-000000000008",
            "52140000-0000-4000-8000-000000000009",
            "52140000-0000-4000-8000-000000000010",
            "52140000-0000-4000-8000-000000000011");
    assertThat(jdbc.queryForObject("""
        select count(*)
        from equipment_balance balance
        join equipment_catalog_item item on item.id = balance.equipment_id
        where balance.quantity <> 0
          and not item.active
        """, Integer.class)).isZero();
    assertThat(jdbc.queryForObject("""
        select count(*)
        from equipment_balance balance
        join equipment_catalog_item item on item.id = balance.equipment_id
        where item.id = '52000000-0000-4000-8000-000000000009'
          and balance.quantity <> 0
        """, Integer.class)).isZero();

    var firstCabin = service.rentalItem(
        UUID.fromString("51000000-0000-4000-8000-000000000001"));
    assertThat(firstCabin.number()).isEqualTo("БЫТ-001");
    assertThat(firstCabin.warehouseId())
        .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    assertThat(firstCabin.contents())
        .extracting(content -> content.equipmentId() + ":" + content.equipmentName() + ":" + content.quantity())
        .containsExactly(
            "52140000-0000-4000-8000-000000000004:Стол обеденный:2",
            "52140000-0000-4000-8000-000000000007:Стул офисный:4",
            "52140000-0000-4000-8000-000000000010:Шкаф для бумаг ЛДСП:1");
    assertThat(jdbc.queryForList("""
        select item.id::text || ':' || balance.quantity
        from equipment_balance balance
        join equipment_catalog_item item on item.id = balance.equipment_id
        where balance.warehouse_id = ?
          and balance.rental_item_id is null
          and balance.location_kind = 'STOCK'
          and balance.quantity > 0
        order by item.id
        """, String.class, firstCabin.warehouseId()))
        .containsExactly(
            "52140000-0000-4000-8000-000000000001:24",
            "52140000-0000-4000-8000-000000000002:24",
            "52140000-0000-4000-8000-000000000003:20",
            "52140000-0000-4000-8000-000000000005:1");
  }
}
