package dev.buhanzaz.rwms.asset;

import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.DIMENSION_24_X_6;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.FINISHING_DVP;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.TYPE_BK_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateRentalItemRequest;
import dev.buhanzaz.rwms.asset.api.RentalItemCreationIntentApiModels.CreateRentalItemWithPhotoIntentRequest;
import dev.buhanzaz.rwms.asset.api.RentalItemCreationIntentApiModels.RentalItemCreationIntentCommand;
import dev.buhanzaz.rwms.asset.api.RentalItemCreationIntentApiModels.RentalItemCreationPhotoManifestEntry;
import dev.buhanzaz.rwms.asset.api.RentalItemCreationIntentApiModels.RentalItemCreationPhotoManifestInput;
import dev.buhanzaz.rwms.asset.domain.OperationLeaseState;
import dev.buhanzaz.rwms.asset.domain.RentalItemCreationIntentState;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.integration.media.MediaCabinCreationSnapshotClient;
import dev.buhanzaz.rwms.asset.integration.media.MediaCabinCreationSnapshotClient.CabinCreationSnapshot;
import dev.buhanzaz.rwms.asset.integration.media.MediaCabinCreationSnapshotClient.ReadyPhoto;
import dev.buhanzaz.rwms.asset.repository.OperationLeaseRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemCreationIntentRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import dev.buhanzaz.rwms.asset.service.AssetConflictException;
import dev.buhanzaz.rwms.asset.service.AssetDependencyException;
import dev.buhanzaz.rwms.asset.service.AssetService;
import dev.buhanzaz.rwms.asset.service.PresentationHoldService;
import dev.buhanzaz.rwms.asset.service.RentalItemCreationIntentService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Proves atomic creation, media completion, quarantine and availability fencing. */
@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.asset.warehouse-registry.enabled=false",
      "spring.cloud.function.definition=",
      "spring.task.scheduling.enabled=false"
    })
@ActiveProfiles("test")
class RentalItemCreationIntentIntegrationTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired RentalItemCreationIntentService creationIntents;
  @Autowired AssetService assets;
  @Autowired PresentationHoldService presentationHolds;
  @Autowired RentalItemCreationIntentRepository intentRepository;
  @Autowired OperationLeaseRepository leaseRepository;
  @Autowired RentalItemRepository rentalItems;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean MediaCabinCreationSnapshotClient media;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.issuer-uri",
        () -> "http://issuer.invalid");
    registry.add("rwms.cors.allowed-origins", () -> "http://localhost:5173");
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }

  @Test
  void createAndLostResponseReplayKeepOneStableIntentAndExcludeAvailability() {
    UUID subjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID key = UUID.randomUUID();
    CreateRentalItemWithPhotoIntentRequest request = request(warehouseId, "PHOTO-CREATE", 2);

    var created = creationIntents.create(subjectId, key, request);
    var replayed = creationIntents.create(subjectId, key, request);

    assertThat(created.replayed()).isFalse();
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.response()).isEqualTo(created.response());
    assertThat(created.response().intent().state())
        .isEqualTo(RentalItemCreationIntentState.PENDING);
    assertThat(created.response().intent().mediaFolderId()).isNotNull();
    assertThat(created.response().intent().mediaCommandId()).isNotNull();
    assertThat(created.response().intent().photoManifestSha256())
        .matches("^[0-9a-f]{64}$");
    assertThat(created.response().intent().photoManifest())
        .extracting(RentalItemCreationPhotoManifestEntry::photoIndex)
        .containsExactly(0, 1);
    assertThat(created.response().intent().photoManifest())
        .extracting(RentalItemCreationPhotoManifestEntry::uploadCommandId)
        .doesNotHaveDuplicates();
    assertThat(creationIntents.get(created.response().intent().id()))
        .isEqualTo(created.response().intent());
    assertThat(creationIntents.listPending(warehouseId, 0, 50).content())
        .contains(created.response().intent());
    assertThat(creationIntents.listPending(UUID.randomUUID(), 0, 50).content())
        .isEmpty();
    assertThat(intentRepository.count()).isGreaterThanOrEqualTo(1);
    var persisted = intentRepository.findById(created.response().intent().id()).orElseThrow();
    assertThat(persisted.getPhotoManifestSha256())
        .isEqualTo(created.response().intent().photoManifestSha256());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from rental_item_creation_photo where intent_id=?",
                Integer.class,
                persisted.getId()))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForList(
                "select photo_index from rental_item_creation_photo where intent_id=? order by photo_index",
                Integer.class,
                persisted.getId()))
        .containsExactly(0, 1);
    var lease = leaseRepository.findById(persisted.getCreationLeaseId()).orElseThrow();
    assertThat(lease.getState()).isEqualTo(OperationLeaseState.ACTIVE);
    assertThat(lease.getOwnerType()).isEqualTo("CABIN_CREATION");
    assertThat(lease.getOwnerId()).isEqualTo(persisted.getId().toString());
    assertThat(lease.getExpiresAt().getYear()).isEqualTo(9999);
    assertThat(
            presentationHolds
                .availableRentalItems(warehouseId, 0, 50, "PHOTO-CREATE")
                .content())
        .extracting(item -> item.id())
        .doesNotContain(created.response().rentalItem().id());
    assertThatThrownBy(
            () ->
                creationIntents.create(
                    subjectId,
                    key,
                    request(warehouseId, "PHOTO-CREATE-CHANGED", 2)))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("Idempotency-Key");
  }

  @Test
  void existingCreateWithoutPhotoIntentRemainsAvailableAndCompatible() {
    UUID warehouseId = UUID.randomUUID();
    var created =
        assets.createRentalItem(
            UUID.randomUUID(),
            UUID.randomUUID(),
            rentalRequest(warehouseId, "NO-PHOTO-INTENT"));

    assertThat(created.response().status()).isEqualTo(RentalItemStatus.FREE);
    assertThat(
            presentationHolds
                .availableRentalItems(warehouseId, 0, 50, "NO-PHOTO-INTENT")
                .content())
        .extracting(item -> item.id())
        .containsExactly(created.response().id());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from rental_item_creation_intent where rental_item_id=?",
                Integer.class,
                created.response().id()))
        .isZero();
  }

  @Test
  void exactReadyMediaProofCompletesAndReplayDoesNotCallMediaAgain() {
    UUID subjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    var created =
        creationIntents
            .create(
                subjectId,
                UUID.randomUUID(),
                request(warehouseId, "PHOTO-COMPLETE", 2))
            .response();
    UUID coverMediaId = UUID.randomUUID();
    UUID secondMediaId = UUID.randomUUID();
    var first = created.intent().photoManifest().get(0);
    var second = created.intent().photoManifest().get(1);
    when(media.read(warehouseId, created.rentalItem().id()))
        .thenReturn(
            Optional.of(
                new CabinCreationSnapshot(
                    created.rentalItem().id(),
                    warehouseId,
                    created.intent().mediaFolderId(),
                    coverMediaId,
                    2,
                    List.of(
                        ready(coverMediaId, 3, first),
                        ready(secondMediaId, 2, second)))));
    UUID key = UUID.randomUUID();
    RentalItemCreationIntentCommand command =
        new RentalItemCreationIntentCommand(created.intent().version());

    var completed =
        creationIntents.complete(subjectId, key, created.intent().id(), command);
    clearInvocations(media);
    var replayed =
        creationIntents.complete(subjectId, key, created.intent().id(), command);

    assertThat(completed.replayed()).isFalse();
    assertThat(completed.response().state())
        .isEqualTo(RentalItemCreationIntentState.COMPLETED);
    assertThat(completed.response().coverMediaId()).isEqualTo(coverMediaId);
    assertThat(completed.response().mediaProofSha256())
        .matches("^[0-9a-f]{64}$");
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.response()).isEqualTo(completed.response());
    verify(media, never()).read(warehouseId, created.rentalItem().id());
    var persisted = intentRepository.findById(created.intent().id()).orElseThrow();
    assertThat(leaseRepository.findById(persisted.getCreationLeaseId()).orElseThrow().getState())
        .isEqualTo(OperationLeaseState.RELEASED);
    assertThat(rentalItems.findById(created.rentalItem().id()).orElseThrow().getStatus())
        .isEqualTo(RentalItemStatus.FREE);
    assertThat(
            presentationHolds
                .availableRentalItems(warehouseId, 0, 50, "PHOTO-COMPLETE")
                .content())
        .extracting(item -> item.id())
        .containsExactly(created.rentalItem().id());
  }

  @Test
  void underfilledOrNonreadyGalleryFailsClosedAndKeepsCreationHold() {
    UUID subjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    var created =
        creationIntents
            .create(
                subjectId,
                UUID.randomUUID(),
                request(warehouseId, "PHOTO-INCOMPLETE", 2))
            .response();
    UUID readyMediaId = UUID.randomUUID();
    var first = created.intent().photoManifest().getFirst();
    when(media.read(warehouseId, created.rentalItem().id()))
        .thenReturn(
            Optional.of(
                new CabinCreationSnapshot(
                    created.rentalItem().id(),
                    warehouseId,
                    created.intent().mediaFolderId(),
                    readyMediaId,
                    2,
                    List.of(ready(readyMediaId, 1, first)))));

    assertThatThrownBy(
            () ->
                creationIntents.complete(
                    subjectId,
                    UUID.randomUUID(),
                    created.intent().id(),
                    new RentalItemCreationIntentCommand(created.intent().version())))
        .isInstanceOf(AssetDependencyException.class)
        .satisfies(
            error ->
                assertThat(((AssetDependencyException) error).status())
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

    var persisted = intentRepository.findById(created.intent().id()).orElseThrow();
    assertThat(persisted.getState()).isEqualTo(RentalItemCreationIntentState.PENDING);
    assertThat(leaseRepository.findById(persisted.getCreationLeaseId()).orElseThrow().getState())
        .isEqualTo(OperationLeaseState.ACTIVE);

    UUID secondMediaId = UUID.randomUUID();
    var second = created.intent().photoManifest().get(1);
    when(media.read(warehouseId, created.rentalItem().id()))
        .thenReturn(
            Optional.of(
                new CabinCreationSnapshot(
                    created.rentalItem().id(),
                    warehouseId,
                    created.intent().mediaFolderId(),
                    readyMediaId,
                    2,
                    List.of(
                        ready(readyMediaId, 1, first),
                        new ReadyPhoto(
                            secondMediaId,
                            1,
                            1,
                            "f".repeat(64),
                            second.contentType(),
                            second.contentLength())))));
    assertThatThrownBy(
            () ->
                creationIntents.complete(
                    subjectId,
                    UUID.randomUUID(),
                    created.intent().id(),
                    new RentalItemCreationIntentCommand(created.intent().version())))
        .isInstanceOf(AssetDependencyException.class)
        .hasMessageContaining("planned source manifest");
  }

  @Test
  void abandonQuarantinesCabinAndWritesOrdinaryStatusAndLeaseAudit() {
    UUID subjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    var created =
        creationIntents
            .create(
                subjectId,
                UUID.randomUUID(),
                request(warehouseId, "PHOTO-ABANDON", 1))
            .response();
    UUID key = UUID.randomUUID();
    RentalItemCreationIntentCommand command =
        new RentalItemCreationIntentCommand(created.intent().version());

    var abandoned = creationIntents.abandon(subjectId, key, created.intent().id(), command);
    var replayed = creationIntents.abandon(subjectId, key, created.intent().id(), command);

    assertThat(abandoned.response().state())
        .isEqualTo(RentalItemCreationIntentState.ABANDONED);
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.response()).isEqualTo(abandoned.response());
    var persisted = intentRepository.findById(created.intent().id()).orElseThrow();
    assertThat(rentalItems.findById(created.rentalItem().id()).orElseThrow().getStatus())
        .isEqualTo(RentalItemStatus.WAREHOUSE);
    assertThat(leaseRepository.findById(persisted.getCreationLeaseId()).orElseThrow().getState())
        .isEqualTo(OperationLeaseState.RELEASED);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from domain_event where aggregate_type='RENTAL_ITEM' and aggregate_id=? and event_type='asset.rental-item.status-changed.v1'",
                Integer.class,
                created.rentalItem().id().toString()))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from domain_event where aggregate_type='OPERATION_LEASE' and aggregate_id=? and event_type='asset.operation-lease.released.v1'",
                Integer.class,
                persisted.getCreationLeaseId().toString()))
        .isOne();
    assertThat(
            presentationHolds
                .availableRentalItems(warehouseId, 0, 50, "PHOTO-ABANDON")
                .content())
        .isEmpty();
  }

  @Test
  void staleExpectedVersionFailsBeforeMediaReadOrMutation() {
    UUID subjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    var created =
        creationIntents
            .create(
                subjectId,
                UUID.randomUUID(),
                request(warehouseId, "PHOTO-STALE", 1))
            .response();

    assertThatThrownBy(
            () ->
                creationIntents.complete(
                    subjectId,
                    UUID.randomUUID(),
                    created.intent().id(),
                    new RentalItemCreationIntentCommand(5L)))
        .isInstanceOf(AssetConflictException.class);
    verify(media, never()).read(warehouseId, created.rentalItem().id());
    assertThatThrownBy(
            () ->
                creationIntents.abandon(
                    subjectId,
                    UUID.randomUUID(),
                    created.intent().id(),
                    new RentalItemCreationIntentCommand(5L)))
        .isInstanceOf(AssetConflictException.class);
    assertThat(intentRepository.findById(created.intent().id()).orElseThrow().getState())
        .isEqualTo(RentalItemCreationIntentState.PENDING);
  }

  private static CreateRentalItemWithPhotoIntentRequest request(
      UUID warehouseId, String number, int expectedPhotoCount) {
    return new CreateRentalItemWithPhotoIntentRequest(
        rentalRequest(warehouseId, number + "-" + UUID.randomUUID()),
        java.util.stream.IntStream.range(0, expectedPhotoCount)
            .mapToObj(
                index ->
                    new RentalItemCreationPhotoManifestInput(
                        index,
                        "%064x".formatted(index + 1),
                        "image/webp",
                        100L + index))
            .toList());
  }

  private static ReadyPhoto ready(
      UUID mediaId, int generation, RentalItemCreationPhotoManifestEntry manifest) {
    return new ReadyPhoto(
        mediaId,
        generation,
        manifest.photoIndex(),
        manifest.checksumSha256(),
        manifest.contentType(),
        manifest.contentLength());
  }

  private static CreateRentalItemRequest rentalRequest(UUID warehouseId, String number) {
    return new CreateRentalItemRequest(
        warehouseId,
        number,
        TYPE_BK_1,
        DIMENSION_24_X_6,
        FINISHING_DVP,
        null,
        List.of(),
        false,
        Map.of(),
        List.of());
  }
}
