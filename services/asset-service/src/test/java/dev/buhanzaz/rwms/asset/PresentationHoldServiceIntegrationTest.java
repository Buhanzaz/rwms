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

import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateRentalItemRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.UpdateStatusRequest;
import dev.buhanzaz.rwms.asset.api.PresentationHoldApiModels.CabinSearchGroup;
import dev.buhanzaz.rwms.asset.api.PresentationHoldApiModels.CabinSearchRequest;
import dev.buhanzaz.rwms.asset.api.PresentationHoldApiModels.CabinAvailabilityRequest;
import dev.buhanzaz.rwms.asset.api.PresentationHoldApiModels.ConvertPresentationHoldsRequest;
import dev.buhanzaz.rwms.asset.api.PresentationHoldApiModels.ReplacePresentationHoldsRequest;
import dev.buhanzaz.rwms.asset.domain.OperationLease;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservationState;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservation;
import dev.buhanzaz.rwms.asset.domain.PresentationUnitHoldState;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.repository.OperationLeaseRepository;
import dev.buhanzaz.rwms.asset.repository.OrderUnitReservationRepository;
import dev.buhanzaz.rwms.asset.repository.PresentationUnitHoldRepository;
import dev.buhanzaz.rwms.asset.service.AssetService;
import dev.buhanzaz.rwms.asset.service.AssetInvalidationHub;
import dev.buhanzaz.rwms.asset.service.AssetNotFoundException;
import dev.buhanzaz.rwms.asset.service.OrderUnitReservationConflictException;
import dev.buhanzaz.rwms.asset.service.PresentationHoldService;
import dev.buhanzaz.rwms.asset.service.RentalAvailabilityInvalidationPublisher;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    var searchResult = presentationHolds.search(search);
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
    assertThat(presentationHolds.search(search).groups())
        .singleElement()
        .satisfies(
            group ->
                assertThat(group.cabins())
                    .extracting(cabin -> cabin.id())
                    .containsExactly(first.id()));
    assertThat(
            presentationHolds
                .search(
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
  void publicAvailablePageExcludesHeldReservedAndLeasedFreeCabins() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    RentalItemResponse available = freeRental(actorSubjectId, warehouseId, "AVAILABLE-MATCH");
    RentalItemResponse held = freeRental(actorSubjectId, warehouseId, "HELD-MATCH");
    RentalItemResponse reserved = freeRental(actorSubjectId, warehouseId, "RESERVED-MATCH");
    RentalItemResponse leased = freeRental(actorSubjectId, warehouseId, "LEASED-MATCH");
    RentalItemResponse notFree = freeRental(actorSubjectId, warehouseId, "NOT-FREE-MATCH");
    assets.updateStatus(
        notFree.id(), new UpdateStatusRequest(notFree.version(), RentalItemStatus.REPAIR));
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

    presentationHolds.search(matchingSearch(warehouseId, firstScope, firstActor));
    UUID holdId =
        presentationHolds.holds(firstScope).holds().getFirst().holdId();
    jdbc.update(
        "update presentation_unit_hold set expires_at=clock_timestamp()-interval '1 second' where id=?",
        holdId);

    var next =
        presentationHolds.search(
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
        presentationHolds.search(
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
        presentationHolds.search(
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

    presentationHolds.search(
        matchingSearch(warehouseId, ownHoldScopeId, actorSubjectId, 1, CATEGORY_NEW));
    presentationHolds.search(
        matchingSearch(
            warehouseId, UUID.randomUUID(), actorSubjectId, 1, CATEGORY_ORDINARY));

    assertThat(presentationHolds.facets(warehouseId, ownHoldScopeId).categories())
        .containsExactly(CATEGORY_NEW);
    assertThat(presentationHolds.facets(warehouseId).categories()).isEmpty();
  }

  @Test
  void searchFiltersCharacteristicsAndLinoleumPerGroupWithoutDuplicateCabins() {
    UUID actorSubjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
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
        presentationHolds.search(
            new CabinSearchRequest(
                warehouseId,
                UUID.randomUUID(),
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10),
                actorSubjectId,
                "RENTAL_MANAGER",
                List.of(detailed, broader)));

    assertThat(searched.groups()).hasSize(2);
    assertThat(searched.groups().get(0).group()).isEqualTo(detailed);
    assertThat(searched.groups().get(0).cabins())
        .hasSize(1)
        .allSatisfy(cabin -> assertThat(cabin.linoleum()).isEqualTo(true));
    assertThat(searched.groups().get(1).group()).isEqualTo(broader);
    assertThat(searched.groups().get(1).cabins())
        .hasSize(2)
        .allSatisfy(cabin -> assertThat(cabin.linoleum()).isEqualTo(true));
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
        presentationHolds.search(
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
            presentationHolds
                .search(
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

  private RentalItemResponse freeRental(UUID actorSubjectId, UUID warehouseId, String suffix) {
    return freeRental(actorSubjectId, warehouseId, suffix, null, null, false);
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
                "БК-1", "ДВП", "2.4x6", category, null, null, quantity)));
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
