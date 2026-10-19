package dev.buhanzaz.rwms.maintenance.disposition.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionAssetKind;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionContentSnapshotLineDraft;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionContentsMode;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionDecision;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionDecisionDraft;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionKind;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionSource;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PropertyDispositionDecisionRepositoryIntegrationTest {
  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired private PropertyDispositionDecisionRepository repository;

  @Test
  @Transactional
  void flywayCreatesTheDecisionSchemaAndPersistsTheFrozenContentsSnapshot() {
    UUID rootRepairId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    PropertyDispositionDecision saved =
        repository.saveAndFlush(
            PropertyDispositionDecision.initiate(
                new PropertyDispositionDecisionDraft(
                    UUID.randomUUID(),
                    PropertyDispositionAssetKind.CABIN,
                    UUID.randomUUID(),
                    "Cabin B-12",
                    PropertyDispositionKind.WRITE_OFF,
                    PropertyDispositionSource.REPAIR,
                    5,
                    null,
                    null,
                    "Structural damage confirmed",
                    null,
                    UUID.randomUUID(),
                    rootRepairId,
                    null,
                    null,
                    null,
                    null,
                    "a".repeat(64),
                    "{\"subjectId\":\"manager-7\",\"principalType\":\"USER\"}",
                    PropertyDispositionContentsMode.MOVE_SELECTED_TO_STOCK,
                    List.of(
                        new PropertyDispositionContentSnapshotLineDraft(
                            equipmentId, "Table", "pcs", 2, 1, 11)))));

    assertThat(saved.getId()).isNotNull();
    assertThat(repository.findByAssetKindAndRootRepairId(
            PropertyDispositionAssetKind.CABIN, rootRepairId))
        .hasValueSatisfying(
            found -> {
              assertThat(found.getId()).isEqualTo(saved.getId());
              assertThat(found.getContents())
                  .singleElement()
                  .satisfies(
                      line -> {
                        assertThat(line.getEquipmentId()).isEqualTo(equipmentId);
                        assertThat(line.getCurrentQuantity()).isEqualTo(2);
                        assertThat(line.getMoveQuantity()).isEqualTo(1);
                        assertThat(line.getExpectedBalanceVersion()).isEqualTo(11);
                      });
            });
  }

  @Test
  @Transactional
  void repairRootIdentityIsUniqueAcrossRepairAndEstimateSources() {
    UUID rootRepairId = UUID.randomUUID();
    repository.saveAndFlush(repairRootDecision(PropertyDispositionSource.REPAIR, rootRepairId));

    assertThatThrownBy(
            () ->
                repository.saveAndFlush(
                    repairRootDecision(PropertyDispositionSource.ESTIMATE, rootRepairId)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @Transactional
  void manualSubjectAndIdempotencyKeyArePermanentlyUniqueAndSupportExactReplayLookup() {
    UUID subjectId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    String firstHash = "a".repeat(64);
    PropertyDispositionDecision first =
        repository.saveAndFlush(manualEquipmentDecision(subjectId, idempotencyKey, firstHash));

    assertThat(repository.findByInitiatedBySubjectIdAndIdempotencyKey(subjectId, idempotencyKey))
        .hasValueSatisfying(
            found -> {
              assertThat(found.getId()).isEqualTo(first.getId());
              assertThat(found.matchesManualReplay(subjectId, idempotencyKey, firstHash)).isTrue();
              assertThat(found.hasManualReplayMismatch(subjectId, idempotencyKey, "b".repeat(64)))
                  .isTrue();
            });
    assertThatThrownBy(
            () ->
                repository.saveAndFlush(
                    manualEquipmentDecision(subjectId, idempotencyKey, "b".repeat(64))))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private static PropertyDispositionDecision repairRootDecision(
      PropertyDispositionSource source, UUID rootRepairId) {
    return PropertyDispositionDecision.initiate(
        new PropertyDispositionDecisionDraft(
            UUID.randomUUID(),
            PropertyDispositionAssetKind.CABIN,
            UUID.randomUUID(),
            "Cabin C-7",
            PropertyDispositionKind.WRITE_OFF,
            source,
            1,
            null,
            null,
            "Root repair decision",
            null,
            UUID.randomUUID(),
            rootRepairId,
            null,
            null,
            null,
            null,
            "c".repeat(64),
            "{\"subjectId\":\"repair-worker\",\"principalType\":\"SERVICE\"}",
            null,
            List.of()));
  }

  private static PropertyDispositionDecision manualEquipmentDecision(
      UUID subjectId, UUID idempotencyKey, String hash) {
    return PropertyDispositionDecision.initiate(
        new PropertyDispositionDecisionDraft(
            UUID.randomUUID(),
            PropertyDispositionAssetKind.EQUIPMENT,
            UUID.randomUUID(),
            "Heater",
            PropertyDispositionKind.LOSS,
            PropertyDispositionSource.MANUAL,
            1,
            2L,
            1L,
            "Manual loss report",
            null,
            null,
            null,
            null,
            null,
            subjectId,
            idempotencyKey,
            hash,
            "{\"subjectId\":\"warehouse-manager\",\"principalType\":\"USER\"}",
            null,
            List.of()));
  }
}
