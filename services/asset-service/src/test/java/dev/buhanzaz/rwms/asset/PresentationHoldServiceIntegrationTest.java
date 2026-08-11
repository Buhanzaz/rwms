package dev.buhanzaz.rwms.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.CHARACTERISTIC_ELECTRICS_KK;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.CATEGORY_NEW;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.CATEGORY_ORDINARY;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.DIMENSION_24_X_6;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.FINISHING_DVP;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.TYPE_BK_1;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.plasticWindow;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireLogisticsEquipmentMovementReservationRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateRentalItemRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsEquipmentMovementPurpose;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.TransferEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.UpdateStatusRequest;
import dev.buhanzaz.rwms.asset.api.PresentationHoldApiModels.CabinSearchGroup;
import dev.buhanzaz.rwms.asset.api.PresentationHoldApiModels.CabinSearchRequest;
import dev.buhanzaz.rwms.asset.api.PresentationHoldApiModels.CabinSearchResponse;
import dev.buhanzaz.rwms.asset.api.PresentationHoldApiModels.CabinAvailabilityRequest;
import dev.buhanzaz.rwms.asset.api.PresentationHoldApiModels.ConvertPresentationHoldsRequest;
import dev.buhanzaz.rwms.asset.api.PresentationHoldApiModels.PresentationHoldView;
import dev.buhanzaz.rwms.asset.api.PresentationHoldApiModels.ReplacePresentationHoldsRequest;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.domain.OperationLease;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservation;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservationState;
import dev.buhanzaz.rwms.asset.domain.PresentationUnitHoldState;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.repository.OperationLeaseRepository;
import dev.buhanzaz.rwms.asset.repository.OrderUnitReservationRepository;
import dev.buhanzaz.rwms.asset.repository.PresentationUnitHoldRepository;
import dev.buhanzaz.rwms.asset.service.AssetService;
import dev.buhanzaz.rwms.asset.service.AssetConflictException;
import dev.buhanzaz.rwms.asset.service.AssetInvalidationHub;
import dev.buhanzaz.rwms.asset.service.AssetNotFoundException;
import dev.buhanzaz.rwms.asset.service.OrderUnitReservationConflictException;
import dev.buhanzaz.rwms.asset.service.PresentationHoldService;
import dev.buhanzaz.rwms.asset.service.RentalAvailabilityInvalidationPublisher;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Proves asset-owned cabin search, hold mutation, expiry and facts lookup semantics. */
@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.asset.warehouse-registry.enabled=false",
      "spring.cloud.function.definition=",
      "spring.task.scheduling.enabled=false"
    })
@ActiveProfiles("test")
class PresentationHoldServiceIntegrationTest {
  private static final UUID LOGISTICS_SUBJECT =
      UUID.nameUUIDFromBytes("service:logistics-service".getBytes(StandardCharsets.UTF_8));
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired PresentationHoldService presentationHolds;
  @Autowired AssetService assets;
  @MockitoSpyBean AssetInvalidationHub invalidations;
  @Autowired PresentationUnitHoldRepository holdRepository;
  @Autowired OrderUnitReservationRepository orderReservations;
  @Autowired OperationLeaseRepository operationLeases;
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
  void freeCabinsAreSearchableThenHeldExclusivelyAndReplacementReplaysSafely() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID presentationId = UUID.randomUUID();
    RentalItemResponse first = freeRental(actorSubjectId, warehouseId, "FIRST");
    CabinSearchRequest search =
        matchingSearch(warehouseId, presentationId, actorSubjectId);

    var searchResult = search(search);
    assertThat(searchResult.expiresAt()).isEqualTo(search.expiresAt());
    assertThat(searchResult.groups())
        .singleElement()
        .satisfies(
            group ->
                assertThat(group.cabins())
                    .extracting(cabin -> cabin.id())
                    .containsExactly(first.id()));

    OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30);
    ReplacePresentationHoldsRequest firstRequest =
        replaceRequest(warehouseId, List.of(first.id()), expiresAt, actorSubjectId);
    UUID firstKey = UUID.randomUUID();
    var acquired = presentationHolds.replace(firstKey, presentationId, firstRequest);
    var replay = presentationHolds.replace(firstKey, presentationId, firstRequest);
    UUID firstHoldId = acquired.response().holds().getFirst().holdId();

    assertThat(acquired.replayed()).isFalse();
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.response()).isEqualTo(acquired.response());
    assertThat(search(search).groups())
        .singleElement()
        .satisfies(
            group ->
                assertThat(group.cabins())
                    .extracting(cabin -> cabin.id())
                    .containsExactly(first.id()));
    assertThat(
            search(
                    matchingSearch(
                        warehouseId, UUID.randomUUID(), UUID.randomUUID()))
                .groups())
        .singleElement()
        .satisfies(group -> assertThat(group.cabins()).isEmpty());
    assertThatThrownBy(
            () ->
                presentationHolds.replace(
                    UUID.randomUUID(), UUID.randomUUID(), firstRequest))
        .isInstanceOf(OrderUnitReservationConflictException.class)
        .satisfies(
            error ->
                assertThat(((OrderUnitReservationConflictException) error).code())
                    .isEqualTo("UNIT_PRESENTATION_HELD"));

    RentalItemResponse second = freeRental(actorSubjectId, warehouseId, "SECOND");
    var replaced =
        presentationHolds.replace(
            UUID.randomUUID(),
            presentationId,
            replaceRequest(
                warehouseId,
                List.of(second.id()),
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30),
                actorSubjectId));

    assertThat(replaced.response().holds())
        .singleElement()
        .extracting(hold -> hold.rentalItemId())
        .isEqualTo(second.id());
    assertThat(holdRepository.findById(firstHoldId).orElseThrow().getState())
        .isEqualTo(PresentationUnitHoldState.RELEASED);
  }

  @Test
  void cabinSearchReplaysFrozenResponseAndRejectsChangedFingerprint() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID holdScopeId = UUID.randomUUID();
    UUID key = UUID.randomUUID();
    freeRental(actorSubjectId, warehouseId, "IDEMPOTENT");
    CabinSearchRequest request =
        matchingSearch(warehouseId, holdScopeId, actorSubjectId, 1);

    var created = presentationHolds.search(LOGISTICS_SUBJECT, key, request);
    var replayed = presentationHolds.search(LOGISTICS_SUBJECT, key, request);

    assertThat(created.replayed()).isFalse();
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.response()).isEqualTo(created.response());
    assertThatThrownBy(
            () ->
                presentationHolds.search(
                    LOGISTICS_SUBJECT,
                    key,
                    matchingSearch(warehouseId, holdScopeId, actorSubjectId, 2)))
        .isInstanceOf(AssetConflictException.class);
    assertThat(presentationHolds.holds(holdScopeId).holds()).hasSize(1);
  }

  @Test
  void cabinSearchAppendPreservesAndRenewsWhileReplaceReleasesIncludingAnEmptyResult() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID holdScopeId = UUID.randomUUID();
    freeRental(actorSubjectId, warehouseId, "MODE-FIRST");
    freeRental(actorSubjectId, warehouseId, "MODE-SECOND");
    freeRental(actorSubjectId, warehouseId, "MODE-THIRD");

    OffsetDateTime firstExpiry = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(2);
    CabinSearchGroup matching =
        new CabinSearchGroup("БК-1", "ДВП", "2.4x6", null, null, null, 1);
    search(
        new CabinSearchRequest(
            warehouseId,
            holdScopeId,
            firstExpiry,
            actorSubjectId,
            "RENTAL_MANAGER",
            List.of(matching),
            "REPLACE"));
    List<PresentationHoldView> firstHolds = presentationHolds.holds(holdScopeId).holds();
    assertThat(firstHolds).hasSize(1);

    OffsetDateTime appendExpiry = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(20);
    CabinSearchResponse appended =
        search(
            new CabinSearchRequest(
                warehouseId,
                holdScopeId,
                appendExpiry,
                actorSubjectId,
                "RENTAL_MANAGER",
                List.of(matching),
                "APPEND"));
    assertThat(appended.groups().getFirst().cabins())
        .singleElement()
        .satisfies(
            cabin ->
                assertThat(cabin.id())
                    .isNotEqualTo(firstHolds.getFirst().rentalItemId()));
    assertThat(presentationHolds.holds(holdScopeId).holds())
        .hasSize(2)
        .allSatisfy(hold -> assertThat(hold.expiresAt()).isAfter(firstExpiry));

    search(
        new CabinSearchRequest(
            warehouseId,
            holdScopeId,
            OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10),
            actorSubjectId,
            "RENTAL_MANAGER",
            List.of(matching),
            null));
    assertThat(presentationHolds.holds(holdScopeId).holds()).hasSize(1);

    CabinSearchGroup noMatch =
        new CabinSearchGroup("НЕСУЩЕСТВУЮЩИЙ ТИП", "ДВП", "2.4x6", null, null, null, 1);
    CabinSearchResponse empty =
        search(
            new CabinSearchRequest(
                warehouseId,
                holdScopeId,
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10),
                actorSubjectId,
                "RENTAL_MANAGER",
                List.of(noMatch),
                "REPLACE"));
    assertThat(empty.groups().getFirst().cabins()).isEmpty();
    assertThat(presentationHolds.holds(holdScopeId).holds()).isEmpty();
  }

  @Test
  void concurrentCabinSearchRetryCreatesOneFrozenResult() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID holdScopeId = UUID.randomUUID();
    UUID key = UUID.randomUUID();
    freeRental(actorSubjectId, warehouseId, "CONCURRENT-IDEMPOTENT");
    CabinSearchRequest request =
        matchingSearch(warehouseId, holdScopeId, actorSubjectId, 1);
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          CompletableFuture.supplyAsync(
              () -> concurrentSearch(start, key, request), executor);
      var second =
          CompletableFuture.supplyAsync(
              () -> concurrentSearch(start, key, request), executor);
      start.countDown();

      var results = List.of(first.join(), second.join());
      assertThat(results).extracting(AssetService.CreateResult::replayed)
          .containsExactlyInAnyOrder(false, true);
      assertThat(results.get(0).response()).isEqualTo(results.get(1).response());
      assertThat(presentationHolds.holds(holdScopeId).holds()).hasSize(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void publicAvailablePageExcludesHeldReservedAndLeasedFreeCabins() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    RentalItemResponse available = freeRental(actorSubjectId, warehouseId, "AVAILABLE-MATCH");
    RentalItemResponse held = freeRental(actorSubjectId, warehouseId, "HELD-MATCH");
    RentalItemResponse reserved = freeRental(actorSubjectId, warehouseId, "RESERVED-MATCH");
    RentalItemResponse leased = freeRental(actorSubjectId, warehouseId, "LEASED-MATCH");
    RentalItemResponse notFree = freeRental(actorSubjectId, warehouseId, "NOT-FREE-MATCH");
    assets.updateStatus(
        notFree.id(), new UpdateStatusRequest(notFree.version(), RentalItemStatus.SALE));
    presentationHolds.replace(
        UUID.randomUUID(),
        UUID.randomUUID(),
        replaceRequest(
            warehouseId,
            List.of(held.id()),
            OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30),
            actorSubjectId));
    orderReservations.saveAndFlush(
        OrderUnitReservation.create(
            UUID.randomUUID(),
            reserved.id(),
            warehouseId,
            actorSubjectId,
            "RENTAL_MANAGER"));
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    operationLeases.saveAndFlush(
        OperationLease.acquire(
            leased.id(),
            "TEST",
            UUID.randomUUID().toString(),
            1,
            UUID.randomUUID(),
            now,
            now.plusMinutes(30)));

    var page = presentationHolds.availableRentalItems(warehouseId, 0, 200, "match");

    assertThat(page.content())
        .extracting(RentalItemResponse::id)
        .containsExactly(available.id());
    assertThat(page.totalElements()).isEqualTo(1);
    assertThat(page.totalPages()).isEqualTo(1);
    assertThat(
            presentationHolds
                .availableRentalItems(warehouseId, Integer.MAX_VALUE, 200, null)
                .content())
        .isEmpty();
    assertThat(
            presentationHolds
                .availability(
                    new CabinAvailabilityRequest(
                        warehouseId,
                        List.of(
                            available.id(),
                            held.id(),
                            reserved.id(),
                            leased.id(),
                            notFree.id())))
                .items())
        .extracting(value -> value.rentalItemId(), value -> value.reason())
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(available.id(), "AVAILABLE"),
            org.assertj.core.groups.Tuple.tuple(held.id(), "PRESENTATION_HELD"),
            org.assertj.core.groups.Tuple.tuple(reserved.id(), "ORDER_RESERVED"),
            org.assertj.core.groups.Tuple.tuple(leased.id(), "OPERATION_LEASED"),
            org.assertj.core.groups.Tuple.tuple(notFree.id(), "STATUS"));
  }

  @Test
  void factsOnlyCatalogSearchesNumberTypeCharacteristicsAndLinoleumWithoutHolds() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    RentalItemResponse linoleum =
        freeRental(
            actorSubjectId,
            warehouseId,
            "CATALOG-LINO",
            CATEGORY_NEW,
            List.of(CHARACTERISTIC_ELECTRICS_KK),
            true);
    RentalItemResponse sale =
        freeRental(actorSubjectId, warehouseId, "CATALOG-SALE", CATEGORY_ORDINARY);
    assets.updateStatus(sale.id(), new UpdateStatusRequest(sale.version(), RentalItemStatus.SALE));

    var byNumber = presentationHolds.catalog(warehouseId, linoleum.number(), 0, 20);
    assertThat(byNumber.content())
        .singleElement()
        .satisfies(
            cabin -> {
              assertThat(cabin.id()).isEqualTo(linoleum.id());
              assertThat(cabin.linoleum()).isTrue();
              assertThat(cabin.characteristics()).isNotBlank();
            });
    assertThat(presentationHolds.catalog(warehouseId, "БК-1", 0, 20).content())
        .extracting(cabin -> cabin.id())
        .containsExactlyInAnyOrder(linoleum.id(), sale.id());
    assertThat(presentationHolds.catalog(warehouseId, "Линолеум", 0, 20).content())
        .extracting(cabin -> cabin.id())
        .containsExactly(linoleum.id());
    assertThat(presentationHolds.catalog(warehouseId, "без линолеума", 0, 20).content())
        .extracting(cabin -> cabin.id())
        .containsExactly(sale.id());
    assertThat(presentationHolds.holds(UUID.randomUUID()).holds()).isEmpty();
  }

  @Test
  void replacingPresentationSelectionPublishesAvailabilityForReleasedAndAcquiredCabins() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID presentationId = UUID.randomUUID();
    RentalItemResponse released = freeRental(actorSubjectId, warehouseId, "SSE-RELEASED");
    RentalItemResponse acquired = freeRental(actorSubjectId, warehouseId, "SSE-ACQUIRED");
    presentationHolds.replace(
        UUID.randomUUID(),
        presentationId,
        replaceRequest(
            warehouseId,
            List.of(released.id()),
            OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30),
            actorSubjectId));
    clearInvocations(invalidations);

    presentationHolds.replace(
        UUID.randomUUID(),
        presentationId,
        replaceRequest(
            warehouseId,
            List.of(acquired.id()),
            OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30),
            actorSubjectId));

    var events =
        org.mockito.ArgumentCaptor.forClass(
            AssetInvalidationHub.AssetInvalidationEvent.class);
    verify(invalidations, org.mockito.Mockito.times(2)).publish(events.capture());
    assertThat(events.getAllValues())
        .allSatisfy(
            event -> {
              assertThat(event.warehouseId()).isEqualTo(warehouseId);
              assertThat(event.scope())
                  .isEqualTo(RentalAvailabilityInvalidationPublisher.CHANGE_TYPE);
              assertThat(event.changeType())
                  .isEqualTo(RentalAvailabilityInvalidationPublisher.CHANGE_TYPE);
            })
        .extracting(AssetInvalidationHub.AssetInvalidationEvent::aggregateId)
        .containsExactlyInAnyOrder(released.id(), acquired.id());
  }

  @Test
  void expiredChatSearchHoldReturnsCabinToAnotherManager() {
    UUID warehouseId = UUID.randomUUID();
    UUID firstScope = UUID.randomUUID();
    UUID firstActor = UUID.randomUUID();
    RentalItemResponse cabin = freeRental(firstActor, warehouseId, "EXPIRING");

    search(matchingSearch(warehouseId, firstScope, firstActor));
    UUID holdId =
        presentationHolds.holds(firstScope).holds().getFirst().holdId();
    jdbc.update(
        "update presentation_unit_hold set expires_at=clock_timestamp()-interval '1 second' where id=?",
        holdId);

    var next =
        search(
            matchingSearch(warehouseId, UUID.randomUUID(), UUID.randomUUID()));

    assertThat(next.groups())
        .singleElement()
        .satisfies(
            group ->
                assertThat(group.cabins())
                    .extracting(found -> found.id())
                    .containsExactly(cabin.id()));
    assertThat(holdRepository.findById(holdId).orElseThrow().getState())
        .isEqualTo(PresentationUnitHoldState.EXPIRED);
  }

  @Test
  void categoryFilterSeparatesFreeCabinsWhileAnUnspecifiedCategoryIncludesAll() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID allWarehouseId = UUID.randomUUID();
    RentalItemResponse ordinary =
        freeRental(actorSubjectId, allWarehouseId, "ALL-ORDINARY", "Обычная");
    RentalItemResponse fresh =
        freeRental(actorSubjectId, allWarehouseId, "ALL-NEW", "Новая");
    assertThat(presentationHolds.facets(allWarehouseId).categories())
        .containsExactly("Новая", "Обычная");

    var all =
        search(
            matchingSearch(
                allWarehouseId,
                UUID.randomUUID(),
                actorSubjectId,
                30,
                null));

    assertThat(all.groups().getFirst().cabins())
        .extracting(cabin -> cabin.id())
        .containsExactlyInAnyOrder(ordinary.id(), fresh.id());
    assertThat(all.groups().getFirst().cabins())
        .allSatisfy(cabin -> assertThat(cabin.status()).isEqualTo(RentalItemStatus.FREE));

    UUID newCategoryWarehouseId = UUID.randomUUID();
    freeRental(actorSubjectId, newCategoryWarehouseId, "NEW-CATEGORY-ORDINARY", "Обычная");
    RentalItemResponse newCategory =
        freeRental(actorSubjectId, newCategoryWarehouseId, "NEW-CATEGORY-NEW", "Новая");
    var onlyNew =
        search(
            matchingSearch(
                newCategoryWarehouseId,
                UUID.randomUUID(),
                actorSubjectId,
                30,
                "Новая"));

    assertThat(onlyNew.groups().getFirst().cabins())
        .singleElement()
        .satisfies(cabin -> {
          assertThat(cabin.id()).isEqualTo(newCategory.id());
          assertThat(cabin.status()).isEqualTo(RentalItemStatus.FREE);
          assertThat(cabin.category()).isEqualTo("Новая");
        });
  }

  @Test
  void facetsIncludeCurrentInquiryHoldsAndExcludeOtherInquiryHolds() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID ownHoldScopeId = UUID.randomUUID();
    freeRental(actorSubjectId, warehouseId, "OWN-FACET", CATEGORY_NEW);
    freeRental(actorSubjectId, warehouseId, "OTHER-FACET", CATEGORY_ORDINARY);

    search(
        matchingSearch(warehouseId, ownHoldScopeId, actorSubjectId, 1, CATEGORY_NEW));
    search(
        matchingSearch(
            warehouseId, UUID.randomUUID(), actorSubjectId, 1, CATEGORY_ORDINARY));

    assertThat(presentationHolds.facets(warehouseId, ownHoldScopeId).categories())
        .containsExactly(CATEGORY_NEW);
    assertThat(presentationHolds.facets(warehouseId, ownHoldScopeId).typeDimensions())
        .singleElement()
        .satisfies(
            relation -> {
              assertThat(relation.cabinType()).isEqualTo("БК-1");
              assertThat(relation.dimensions()).containsExactly("2.4x6");
            });
    assertThat(presentationHolds.facets(warehouseId).categories()).isEmpty();
  }

  @Test
  void searchFiltersCharacteristicsAndLinoleumPerGroupWithoutDuplicateCabins() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID holdScopeId = UUID.randomUUID();
    RentalItemResponse first =
        freeRental(
            actorSubjectId,
            warehouseId,
            "LLM-MATCH-FIRST",
            "Новая",
            plasticWindow(),
            true);
    RentalItemResponse second =
        freeRental(
            actorSubjectId,
            warehouseId,
            "LLM-MATCH-SECOND",
            "Новая",
            plasticWindow(),
            true);
    RentalItemResponse third =
        freeRental(
            actorSubjectId,
            warehouseId,
            "LLM-MATCH-THIRD",
            "Новая",
            plasticWindow(),
            true);
    freeRental(
        actorSubjectId,
        warehouseId,
        "LLM-AAA-WRONG-LINOLEUM",
        "Новая",
        plasticWindow(),
        false);
    freeRental(
        actorSubjectId,
        warehouseId,
        "LLM-AAB-WRONG-CHARACTERISTICS",
        "Новая",
        List.of(CHARACTERISTIC_ELECTRICS_KK),
        true);
    CabinSearchGroup detailed =
        new CabinSearchGroup(
            "БК-1", "ДВП", "2.4x6", "Новая", " пластиковое  окно ", true, 1);
    CabinSearchGroup broader =
        new CabinSearchGroup("БК-1", "ДВП", "2.4x6", "Новая", "ПЛАСТИКОВОЕ", true, 2);

    var searched =
        search(
            new CabinSearchRequest(
                warehouseId,
                holdScopeId,
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10),
                actorSubjectId,
                "RENTAL_MANAGER",
                List.of(detailed, broader),
                null));

    assertThat(searched.groups()).hasSize(2);
    assertThat(searched.groups().get(0).group()).isEqualTo(detailed);
    assertThat(searched.groups().get(0).cabins())
        .hasSize(1)
        .allSatisfy(cabin -> assertThat(cabin.linoleum()).isEqualTo(true));
    assertThat(searched.groups().get(1).group()).isEqualTo(broader);
    assertThat(searched.groups().get(1).cabins())
        .hasSize(2)
        .allSatisfy(cabin -> assertThat(cabin.linoleum()).isEqualTo(true));
    assertThat(presentationHolds.facets(warehouseId, holdScopeId).characteristics())
        .contains("Пластиковое окно");
    List<UUID> selectedIds =
        searched.groups().stream()
            .flatMap(group -> group.cabins().stream())
            .map(cabin -> cabin.id())
            .toList();
    assertThat(selectedIds).containsExactlyInAnyOrder(first.id(), second.id(), third.id());
  }

  @Test
  void publishingSelectionExtendsChosenCabinAndImmediatelyReleasesTheRest() {
    UUID warehouseId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID actorSubjectId = UUID.randomUUID();
    RentalItemResponse first = freeRental(actorSubjectId, warehouseId, "PUBLISH-FIRST");
    RentalItemResponse second = freeRental(actorSubjectId, warehouseId, "PUBLISH-SECOND");
    var searched =
        search(
            matchingSearch(warehouseId, inquiryId, actorSubjectId, 2));
    assertThat(searched.groups().getFirst().cabins()).hasSize(2);
    UUID secondHoldId =
        holdIdFor(
            presentationHolds.holds(inquiryId).holds(),
            second.id());
    OffsetDateTime publicExpiry =
        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(60);

    var published =
        presentationHolds.replace(
            UUID.randomUUID(),
            inquiryId,
            replaceRequest(
                warehouseId, List.of(first.id()), publicExpiry, actorSubjectId));

    assertThat(published.response().expiresAt()).isEqualTo(publicExpiry);
    assertThat(published.response().holds())
        .singleElement()
        .extracting(hold -> hold.rentalItemId())
        .isEqualTo(first.id());
    assertThat(holdRepository.findById(secondHoldId).orElseThrow().getState())
        .isEqualTo(PresentationUnitHoldState.RELEASED);
    assertThat(
            search(
                    matchingSearch(
                        warehouseId, UUID.randomUUID(), UUID.randomUUID()))
                .groups()
                .getFirst()
                .cabins())
        .extracting(cabin -> cabin.id())
        .containsExactly(second.id());
  }

  @Test
  void manualDraftHoldsTransferAtomicallyAndReleaseUnselectedCabins() {
    UUID warehouseId = UUID.randomUUID();
    UUID manualDraftId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID actorSubjectId = UUID.randomUUID();
    RentalItemResponse selected =
        freeRental(actorSubjectId, warehouseId, "MANUAL-SELECTED");
    RentalItemResponse unselected =
        freeRental(actorSubjectId, warehouseId, "MANUAL-UNSELECTED");
    var manual =
        presentationHolds.replace(
            UUID.randomUUID(),
            manualDraftId,
            replaceRequest(
                warehouseId,
                List.of(selected.id(), unselected.id()),
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30),
                actorSubjectId));
    UUID selectedHoldId = holdIdFor(manual.response().holds(), selected.id());
    UUID unselectedHoldId = holdIdFor(manual.response().holds(), unselected.id());

    assertThatThrownBy(
            () ->
                presentationHolds.holds(
                    manualDraftId, UUID.randomUUID(), "RENTAL_MANAGER"))
        .isInstanceOf(AssetNotFoundException.class);
    assertThatThrownBy(
            () ->
                presentationHolds.replace(
                    UUID.randomUUID(),
                    manualDraftId,
                    replaceRequest(
                        warehouseId,
                        List.of(selected.id()),
                        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30),
                        UUID.randomUUID())))
        .isInstanceOf(AssetNotFoundException.class);

    OffsetDateTime presentationExpiry =
        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(60);
    var transferred =
        presentationHolds.replace(
            UUID.randomUUID(),
            inquiryId,
            replaceRequest(
                warehouseId,
                List.of(selected.id()),
                presentationExpiry,
                actorSubjectId,
                manualDraftId));

    assertThat(transferred.response().holds())
        .singleElement()
        .satisfies(
            hold -> {
              assertThat(hold.holdId()).isEqualTo(selectedHoldId);
              assertThat(hold.presentationId()).isEqualTo(inquiryId);
              assertThat(hold.expiresAt()).isEqualTo(presentationExpiry);
            });
    assertThat(presentationHolds.holds(manualDraftId).holds()).isEmpty();
    assertThat(holdRepository.findById(unselectedHoldId).orElseThrow().getState())
        .isEqualTo(PresentationUnitHoldState.RELEASED);
    assertThat(holdRepository.findById(selectedHoldId).orElseThrow().getPresentationId())
        .isEqualTo(inquiryId);
  }

  @Test
  void conversionBooksSelectedCabinAndReleasesTheRestOfThePresentation() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID presentationId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    RentalItemResponse selected = freeRental(actorSubjectId, warehouseId, "SELECTED");
    RentalItemResponse unselected = freeRental(actorSubjectId, warehouseId, "UNSELECTED");
    var held =
        presentationHolds.replace(
            UUID.randomUUID(),
            presentationId,
            replaceRequest(
                warehouseId,
                List.of(selected.id(), unselected.id()),
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30),
                actorSubjectId));
    UUID selectedHoldId = holdIdFor(held.response().holds(), selected.id());
    UUID unselectedHoldId = holdIdFor(held.response().holds(), unselected.id());

    var converted =
        presentationHolds.convert(
            UUID.randomUUID(),
            presentationId,
            new ConvertPresentationHoldsRequest(
                orderId,
                warehouseId,
                List.of(selected.id()),
                UUID.randomUUID(),
                "ООО Тестовый арендатор",
                actorSubjectId,
                "RENTAL_MANAGER"));

    assertThat(converted.replayed()).isFalse();
    assertThat(converted.response().reservations())
        .singleElement()
        .satisfies(
            reservation -> {
              assertThat(reservation.orderId()).isEqualTo(orderId);
              assertThat(reservation.rentalItemId()).isEqualTo(selected.id());
              assertThat(reservation.state()).isEqualTo("ACTIVE");
            });
    assertThat(converted.response().releasedRentalItemIds()).containsExactly(unselected.id());
    assertThat(
            orderReservations.findByOrderIdAndRentalItemIdAndState(
                orderId, selected.id(), OrderUnitReservationState.ACTIVE))
        .isPresent();
    assertThat(assets.rentalItem(selected.id()).status()).isEqualTo(RentalItemStatus.BOOKED);
    assertThat(assets.rentalItem(unselected.id()).status()).isEqualTo(RentalItemStatus.FREE);
    assertThat(holdRepository.findById(selectedHoldId).orElseThrow().getState())
        .isEqualTo(PresentationUnitHoldState.CONVERTED);
    assertThat(holdRepository.findById(unselectedHoldId).orElseThrow().getState())
        .isEqualTo(PresentationUnitHoldState.RELEASED);
  }

  @Test
  void heldCabinSnapshotsAreOrderedAndFenceContentsAgainstEveryMovementOrdering() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID equipmentId =
        assets
            .createEquipment(
                actorSubjectId,
                UUID.randomUUID(),
                new CreateEquipmentRequest(
                    "Presentation bed " + UUID.randomUUID(),
                    EquipmentCategory.FURNITURE,
                    null,
                    4))
            .response()
            .id();
    RentalItemResponse first = freeRental(actorSubjectId, warehouseId, "SNAPSHOT-FIRST");
    RentalItemResponse second = freeRental(actorSubjectId, warehouseId, "SNAPSHOT-SECOND");
    UUID firstBalanceId =
        seedBalance(
            equipmentId,
            warehouseId,
            first.id(),
            BalanceLocationKind.CABIN_NON_RENTED,
            4);
    seedBalance(equipmentId, warehouseId, null, BalanceLocationKind.STOCK, 0);

    UUID presentationId = UUID.randomUUID();
    var held =
        presentationHolds.replace(
            UUID.randomUUID(),
            presentationId,
            replaceRequest(
                warehouseId,
                List.of(second.id(), first.id()),
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10),
                actorSubjectId));
    assertThat(held.response().cabins())
        .extracting(cabin -> cabin.id())
        .containsExactly(second.id(), first.id());
    assertThat(held.response().cabins().get(1).contents())
        .singleElement()
        .satisfies(
            content -> {
              assertThat(content.equipmentId()).isEqualTo(equipmentId);
              assertThat(content.quantity()).isEqualTo(4);
            });
    assertThat(assets.equipmentTotals(equipmentId, warehouseId).availableQuantity()).isZero();
    assertThatThrownBy(
            () ->
                assets.transfer(
                    actorSubjectId,
                    UUID.randomUUID(),
                    new TransferEquipmentRequest(
                        equipmentId,
                        warehouseId,
                        first.id(),
                        BalanceLocationKind.CABIN_NON_RENTED,
                        0L,
                        warehouseId,
                        null,
                        BalanceLocationKind.STOCK,
                        0L,
                        1L)))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("Presentation-held rental item contents");
    assertThat(
            jdbc.queryForObject(
                "select quantity from equipment_balance where id=?", Long.class, firstBalanceId))
        .isEqualTo(4L);

    RentalItemResponse movementSource =
        freeRental(actorSubjectId, warehouseId, "MOVEMENT-FIRST");
    UUID movementBalanceId =
        seedBalance(
            equipmentId,
            warehouseId,
            movementSource.id(),
            BalanceLocationKind.CABIN_NON_RENTED,
            1);
    assets.acquireLogisticsEquipmentMovementReservation(
        actorSubjectId,
        UUID.randomUUID(),
        new AcquireLogisticsEquipmentMovementReservationRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            LogisticsEquipmentMovementPurpose.ALLOCATABLE_REBALANCE,
            equipmentId,
            warehouseId,
            movementSource.id(),
            BalanceLocationKind.CABIN_NON_RENTED,
            0L,
            1L,
            OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10)));
    RentalItemResponse rollbackCandidate =
        freeRental(actorSubjectId, warehouseId, "ROLLBACK-CANDIDATE");
    UUID failedPresentationId = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                presentationHolds.replace(
                    UUID.randomUUID(),
                    failedPresentationId,
                    replaceRequest(
                        warehouseId,
                        List.of(rollbackCandidate.id(), movementSource.id()),
                        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10),
                        actorSubjectId)))
        .isInstanceOf(OrderUnitReservationConflictException.class)
        .satisfies(
            error ->
                assertThat(((OrderUnitReservationConflictException) error).code())
                    .isEqualTo("UNIT_EQUIPMENT_MOVEMENT_FENCED"));
    assertThat(presentationHolds.holds(failedPresentationId).holds()).isEmpty();
    assertThat(presentationHolds.holds(failedPresentationId).cabins()).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select quantity from equipment_balance where id=?", Long.class, movementBalanceId))
        .isEqualTo(1L);
  }

  private RentalItemResponse freeRental(UUID actorSubjectId, UUID warehouseId, String suffix) {
    return freeRental(actorSubjectId, warehouseId, suffix, null, null, false);
  }

  private UUID seedBalance(
      UUID equipmentId,
      UUID warehouseId,
      UUID rentalItemId,
      BalanceLocationKind locationKind,
      long quantity) {
    UUID balanceId = UUID.randomUUID();
    jdbc.update(
        """
        insert into equipment_balance(
          id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity,
          created_at,updated_at)
        values (?,0,?,?,?,?,?,clock_timestamp(),clock_timestamp())
        """,
        balanceId,
        equipmentId,
        warehouseId,
        rentalItemId,
        locationKind.name(),
        quantity);
    return balanceId;
  }

  private CabinSearchResponse search(CabinSearchRequest request) {
    return presentationHolds
        .search(LOGISTICS_SUBJECT, UUID.randomUUID(), request)
        .response();
  }

  private AssetService.CreateResult<CabinSearchResponse> concurrentSearch(
      CountDownLatch start, UUID key, CabinSearchRequest request) {
    try {
      start.await();
      return presentationHolds.search(LOGISTICS_SUBJECT, key, request);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Concurrent search test was interrupted", interrupted);
    }
  }

  private RentalItemResponse freeRental(
      UUID actorSubjectId, UUID warehouseId, String suffix, String category) {
    return freeRental(actorSubjectId, warehouseId, suffix, category, null, false);
  }

  private RentalItemResponse freeRental(
      UUID actorSubjectId,
      UUID warehouseId,
      String suffix,
      String category,
      List<UUID> characteristicIds,
      boolean linoleum) {
    return
        assets
            .createRentalItem(
                actorSubjectId,
                UUID.randomUUID(),
                new CreateRentalItemRequest(
                    warehouseId,
                    "PRESENTATION-" + suffix + "-" + UUID.randomUUID(),
                    TYPE_BK_1,
                    DIMENSION_24_X_6,
                    FINISHING_DVP,
                    category,
                    characteristicIds == null ? List.of() : characteristicIds,
                    linoleum,
                    Map.of(),
                    List.of()))
            .response();
  }

  private static CabinSearchRequest matchingSearch(
      UUID warehouseId, UUID holdScopeId, UUID actorSubjectId) {
    return matchingSearch(warehouseId, holdScopeId, actorSubjectId, 1);
  }

  private static CabinSearchRequest matchingSearch(
      UUID warehouseId, UUID holdScopeId, UUID actorSubjectId, int quantity) {
    return matchingSearch(
        warehouseId,
        holdScopeId,
        actorSubjectId,
        quantity,
        null);
  }

  private static CabinSearchRequest matchingSearch(
      UUID warehouseId,
      UUID holdScopeId,
      UUID actorSubjectId,
      int quantity,
      String category) {
    return new CabinSearchRequest(
        warehouseId,
        holdScopeId,
        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10),
        actorSubjectId,
        "RENTAL_MANAGER",
        List.of(
            new CabinSearchGroup(
                "БК-1", "ДВП", "2.4x6", category, null, null, quantity)),
        null);
  }

  private static ReplacePresentationHoldsRequest replaceRequest(
      UUID warehouseId, List<UUID> rentalItemIds, OffsetDateTime expiresAt, UUID actorSubjectId) {
    return replaceRequest(warehouseId, rentalItemIds, expiresAt, actorSubjectId, null);
  }

  private static ReplacePresentationHoldsRequest replaceRequest(
      UUID warehouseId,
      List<UUID> rentalItemIds,
      OffsetDateTime expiresAt,
      UUID actorSubjectId,
      UUID sourceHoldScopeId) {
    return new ReplacePresentationHoldsRequest(
        warehouseId,
        rentalItemIds,
        expiresAt,
        actorSubjectId,
        "RENTAL_MANAGER",
        sourceHoldScopeId);
  }

  private static UUID holdIdFor(
      List<dev.buhanzaz.rwms.asset.api.PresentationHoldApiModels.PresentationHoldView> holds,
      UUID rentalItemId) {
    return holds.stream()
        .filter(hold -> hold.rentalItemId().equals(rentalItemId))
        .findFirst()
        .orElseThrow()
        .holdId();
  }
}
