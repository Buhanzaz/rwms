package dev.buhanzaz.rwms.logistics.inquiry;

import static dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.inquiry.domain.PresentationBookingManagerAction;
import dev.buhanzaz.rwms.logistics.inquiry.service.ClientPresentationService;
import dev.buhanzaz.rwms.logistics.inquiry.service.PresentationBookingService;
import dev.buhanzaz.rwms.logistics.inquiry.service.RentalBookingAlertService;
import dev.buhanzaz.rwms.logistics.inquiry.service.RentalInquiryService;
import dev.buhanzaz.rwms.logistics.inquiry.service.RentalSettingsService;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.NewClientInput;
import dev.buhanzaz.rwms.logistics.order.domain.ClientType;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.task.scheduling.enabled=false",
      "rwms.logistics.rental-inquiry.booking-retry-initial-delay=1h",
      "rwms.logistics.rental-inquiry.booking-retry-delay=1h",
      "rwms.platform.kafka.enabled=false",
      "rwms.logistics.rental-inquiry.outbox-enabled=false",
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RentalInquiryPresentationIntegrationTest {
  private static final UUID MANAGER =
      UUID.fromString("00000000-0000-4000-8000-000000007101");
  private static final UUID OTHER_MANAGER =
      UUID.fromString("00000000-0000-4000-8000-000000007102");
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-4000-8000-000000007201");
  private static final UUID CABIN_1 =
      UUID.fromString("00000000-0000-4000-8000-000000007301");
  private static final UUID CABIN_2 =
      UUID.fromString("00000000-0000-4000-8000-000000007302");
  private static final UUID PHOTO =
      UUID.fromString("00000000-0000-4000-8000-000000007401");

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine");

  @Autowired RentalInquiryService inquiries;
  @Autowired ClientPresentationService presentations;
  @Autowired PresentationBookingService bookings;
  @Autowired RentalBookingAlertService bookingAlerts;
  @Autowired RentalSettingsService settings;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean LogisticsDependencyGateway dependencies;

  private final Map<UUID, List<LogisticsDependencyGateway.OrderUnitReservation>>
      orderReservations = new LinkedHashMap<>();
  private final Map<UUID, List<UUID>> releasedCabinsByOrder = new LinkedHashMap<>();
  private final Map<UUID, LogisticsDependencyGateway.PresentationHolds>
      presentationHolds = new LinkedHashMap<>();
  private final OrderActor actor =
      new OrderActor(
          MANAGER,
          "RENTAL_MANAGER",
          "Менеджер аренды",
          Set.of(WAREHOUSE),
          Set.of(WAREHOUSE),
          false,
          false,
          true,
          true);
  private final OrderActor otherManager =
      new OrderActor(
          OTHER_MANAGER,
          "RENTAL_MANAGER",
          "Другой менеджер аренды",
          Set.of(WAREHOUSE),
          Set.of(WAREHOUSE),
          false,
          false,
          true,
          true);

  @BeforeEach
  void resetState() {
    jdbc.execute(
        """
        truncate table
          rental_inquiry_outbox,
          presentation_booking,
          client_presentation_item,
          client_presentation,
          rental_inquiry,
          rental_order_command_receipt,
          rental_order_audit_event,
          rental_order,
          order_client
        cascade
        """);
    orderReservations.clear();
    releasedCabinsByOrder.clear();
    presentationHolds.clear();
    reset(dependencies);
    jdbc.update(
        """
        update rental_settings
        set version=0,
            chat_selection_hold_minutes=10,
            presentation_hold_minutes=60,
            updated_by_subject_id='00000000-0000-0000-0000-000000000000',
            updated_at=clock_timestamp()
        """);
    when(dependencies.readWarehouseIdentity(WAREHOUSE))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseIdentity(
                WAREHOUSE,
                0,
                true,
                "Санкт-Петербург",
                "Санкт-Петербург",
                "Europe/Moscow"));
    when(dependencies.readOrderUnits(any()))
        .thenAnswer(
            invocation ->
                orderReservations.getOrDefault(invocation.getArgument(0), List.of()));
    when(dependencies.readCabinSnapshots(eq(WAREHOUSE), anyList()))
        .thenAnswer(
            invocation -> {
              List<UUID> ids = invocation.getArgument(1);
              return ids.stream().map(this::cabin).toList();
            });
    when(dependencies.readCabinMediaSnapshots(eq(WAREHOUSE), anyList()))
        .thenAnswer(
            invocation -> {
              List<UUID> ids = invocation.getArgument(1);
              return ids.stream()
                  .map(
                      id ->
                          new LogisticsDependencyGateway.CabinMediaSnapshot(
                              id,
                              List.of(
                                  new LogisticsDependencyGateway.CabinMediaPhoto(
                                      PHOTO, 1, 0, List.of("SMALL", "LARGE")))))
                  .toList();
            });
    when(
            dependencies.replacePresentationHolds(
                any(), any(), eq(WAREHOUSE), anyList(), any(), eq(MANAGER), eq("RENTAL_MANAGER")))
        .thenAnswer(
            invocation -> {
              UUID presentationId = invocation.getArgument(1);
              List<UUID> ids = invocation.getArgument(3);
              OffsetDateTime expiresAt = invocation.getArgument(4);
              List<LogisticsDependencyGateway.PresentationHold> holds = new ArrayList<>();
              for (UUID id : ids) {
                holds.add(
                    new LogisticsDependencyGateway.PresentationHold(
                        UUID.randomUUID(),
                        0,
                        presentationId,
                        id,
                        WAREHOUSE,
                        "ACTIVE",
                        expiresAt,
                        null,
                        now(),
                        null));
              }
              LogisticsDependencyGateway.PresentationHolds result =
                  new LogisticsDependencyGateway.PresentationHolds(
                  presentationId, expiresAt, List.copyOf(holds));
              presentationHolds.put(presentationId, result);
              return result;
            });
    when(dependencies.readPresentationHolds(any()))
        .thenAnswer(
            invocation -> {
              UUID scopeId = invocation.getArgument(0);
              return presentationHolds.getOrDefault(
                  scopeId,
                  new LogisticsDependencyGateway.PresentationHolds(
                      scopeId, null, List.of()));
            });
    when(
            dependencies.convertPresentationHolds(
                any(),
                any(),
                any(),
                eq(WAREHOUSE),
                anyList(),
                any(),
                anyString(),
                eq(MANAGER),
                eq("RENTAL_MANAGER")))
        .thenAnswer(
            invocation -> {
              UUID presentationId = invocation.getArgument(1);
              UUID orderId = invocation.getArgument(2);
              List<UUID> selected = invocation.getArgument(4);
              List<LogisticsDependencyGateway.OrderUnitReservation> reservations =
                  selected.stream().map(id -> reservation(orderId, id)).toList();
              orderReservations.put(orderId, reservations);
              List<UUID> released =
                  List.of(CABIN_1, CABIN_2).stream()
                      .filter(id -> !selected.contains(id))
                      .toList();
              releasedCabinsByOrder.put(orderId, released);
              return new LogisticsDependencyGateway.ConvertedPresentationHolds(
                  presentationId, orderId, reservations, released);
            });
    when(dependencies.releaseAllOrderUnits(any(), any(), any(), anyString()))
        .thenReturn(List.of());
    when(dependencies.releasePresentationHolds(any(), any(), any(), anyString()))
        .thenAnswer(
            invocation -> {
              UUID scopeId = invocation.getArgument(1);
              presentationHolds.remove(scopeId);
              return new LogisticsDependencyGateway.PresentationHolds(
                  scopeId, null, List.of());
            });
  }

  @Test
  void cabinSearchUsesConfiguredHoldOwnedByTheInquiryAndExactCategory() {
    jdbc.update(
        "update rental_settings set chat_selection_hold_minutes=17");
    RentalInquiryResponse inquiry = createInquiry();
    when(
            dependencies.searchAvailableCabins(
                eq(WAREHOUSE),
                eq(inquiry.id()),
                any(),
                eq(MANAGER),
                eq("RENTAL_MANAGER"),
                anyList()))
        .thenAnswer(
            invocation -> {
              OffsetDateTime expiresAt = invocation.getArgument(2);
              List<?> groups = invocation.getArgument(5);
              LogisticsDependencyGateway.CabinSearchGroup group =
                  (LogisticsDependencyGateway.CabinSearchGroup) groups.getFirst();
              assertThat(group.category()).isEqualTo("Новая");
              return new LogisticsDependencyGateway.CabinSearchResult(
                  WAREHOUSE,
                  expiresAt,
                  List.of(
                      new LogisticsDependencyGateway.CabinSearchGroupResult(
                          group, List.of(cabin(CABIN_1)))));
            });
    OffsetDateTime startedAt = now();

    CabinSearchResponse result =
        inquiries.search(
            actor,
            inquiry.id(),
            new CabinSearchRequest(
                WAREHOUSE,
                List.of(
                    new CabinSearchGroup(
                        "БК-1", "ДВП", null, "Новая", null, null, 2))));

    assertThat(result.expiresAt())
        .isAfter(startedAt.plusMinutes(16))
        .isBefore(startedAt.plusMinutes(18));
    assertThat(result.groups())
        .singleElement()
        .satisfies(
            group -> {
              assertThat(group.cabins())
                  .extracting(AvailableCabinResponse::id)
                  .containsExactly(CABIN_1);
              assertThat(group.group().category()).isEqualTo("Новая");
              assertThat(group.cabins())
                  .extracting(AvailableCabinResponse::status)
                  .containsExactly("FREE");
            });
  }

  @Test
  void cabinSearchPreservesRichGroupsAndQuantitiesThroughThePublicBoundary() {
    RentalInquiryResponse inquiry = createInquiry();
    CabinSearchGroup first =
        new CabinSearchGroup(
            "БК-1", "ДВП", "6x2.4", "Новая", "Утеплённая с электрикой", true, 6);
    CabinSearchGroup second =
        new CabinSearchGroup(
            "БК-2", "OSB", "6x2.4", "ИТР", "С дополнительной вентиляцией", false, 6);
    when(
            dependencies.searchAvailableCabins(
                eq(WAREHOUSE),
                eq(inquiry.id()),
                any(),
                eq(MANAGER),
                eq("RENTAL_MANAGER"),
                anyList()))
        .thenAnswer(
            invocation -> {
              OffsetDateTime expiresAt = invocation.getArgument(2);
              List<LogisticsDependencyGateway.CabinSearchGroup> groups = invocation.getArgument(5);
              assertThat(groups)
                  .containsExactly(
                      new LogisticsDependencyGateway.CabinSearchGroup(
                          "БК-1",
                          "ДВП",
                          "6x2.4",
                          "Новая",
                          "Утеплённая с электрикой",
                          true,
                          6),
                      new LogisticsDependencyGateway.CabinSearchGroup(
                          "БК-2",
                          "OSB",
                          "6x2.4",
                          "ИТР",
                          "С дополнительной вентиляцией",
                          false,
                          6));
              return new LogisticsDependencyGateway.CabinSearchResult(
                  WAREHOUSE,
                  expiresAt,
                  List.of(
                      new LogisticsDependencyGateway.CabinSearchGroupResult(
                          groups.get(0), List.of(cabin(CABIN_1))),
                      new LogisticsDependencyGateway.CabinSearchGroupResult(
                          groups.get(1), List.of(cabin(CABIN_2)))));
            });

    CabinSearchResponse result =
        inquiries.search(actor, inquiry.id(), new CabinSearchRequest(WAREHOUSE, List.of(first, second)));

    assertThat(result.groups())
        .extracting(CabinSearchGroupResult::group)
        .containsExactly(first, second);
    assertThat(result.groups().get(0).cabins())
        .extracting(AvailableCabinResponse::status)
        .containsExactly("FREE");
    assertThat(result.groups().get(1).cabins())
        .extracting(AvailableCabinResponse::status)
        .containsExactly("FREE");
  }

  @Test
  void cabinFacetsUsesTheInquiryAsTheAssetHoldScope() {
    RentalInquiryResponse inquiry = createInquiry();
    when(dependencies.readAvailableCabinFacets(WAREHOUSE, inquiry.id()))
        .thenReturn(
            new LogisticsDependencyGateway.CabinFacets(
                WAREHOUSE,
                List.of("БК-1"),
                List.of("ДВП"),
                List.of("6x2.4"),
                List.of("Новая")));

    CabinFacetsResponse result = inquiries.facets(actor, inquiry.id());

    assertThat(result.warehouses())
        .singleElement()
        .satisfies(
            warehouse -> {
              assertThat(warehouse.warehouseId()).isEqualTo(WAREHOUSE);
              assertThat(warehouse.cabinTypes()).containsExactly("БК-1");
            });
    verify(dependencies).readAvailableCabinFacets(WAREHOUSE, inquiry.id());
  }

  @Test
  void administratorsUpdateChatAndPresentationDurationsTogether() {
    OrderActor administrator =
        new OrderActor(
            MANAGER,
            "SYSTEM_ADMIN",
            "Системный администратор",
            Set.of(),
            Set.of(),
            true,
            true,
            true,
            true);
    RentalSettingsResponse initial = settings.get(administrator);

    RentalSettingsResponse updated =
        settings.update(
            administrator,
            new UpdateRentalSettingsRequest(initial.version(), 12, 90, 2_880));

    assertThat(updated.version()).isEqualTo(initial.version() + 1);
    assertThat(updated.chatSelectionHoldMinutes()).isEqualTo(12);
    assertThat(updated.presentationHoldMinutes()).isEqualTo(90);
    assertThat(updated.draftReservationHoldMinutes()).isEqualTo(2_880);
    assertThat(updated.updatedBy()).isEqualTo(MANAGER);
  }

  @Test
  void publishedSelectionCreatesOneDraftForOriginalManagerAndArchivesInquiry() {
    RentalInquiryResponse inquiry = createInquiry();
    ClientPresentationResponse published = publish(inquiry.id(), List.of(CABIN_1, CABIN_2));
    String token = token(published);

    PublicClientPresentationResponse publicView = presentations.publicPresentation(token);
    assertThat(publicView.viewOnly()).isFalse();
    assertThat(publicView.groups()).singleElement().satisfies(
        group -> {
          assertThat(group.label()).isEqualTo("БК-1");
          assertThat(group.cabins()).hasSize(2);
          assertThat(group.cabins().getFirst().passport())
              .containsEntry("wall", "ДВП")
              .doesNotContainKey("authorAction");
          assertThat(group.cabins().getFirst().photos())
              .singleElement()
              .satisfies(
                  photo -> {
                    assertThat(photo.thumbnailUrl()).contains(token, "/SMALL");
                    assertThat(photo.contentUrl()).contains(token, "/LARGE");
                  });
        });

    UUID bookingKey = UUID.randomUUID();
    PresentationBookingResponse booking =
        bookings.confirm(
            token, bookingKey, new ConfirmClientPresentationRequest(List.of(CABIN_1)));

    assertThat(booking.state()).isEqualTo("COMPLETED");
    assertThat(booking.orderId()).isNotNull();
    Map<String, Object> order =
        jdbc.queryForMap(
            """
            select status,manager_id,client_id,warehouse_id
            from rental_order where id=?
            """,
            booking.orderId());
    assertThat(order)
        .containsEntry("status", "DRAFT")
        .containsEntry("manager_id", MANAGER)
        .containsEntry("client_id", inquiry.client().id())
        .containsEntry("warehouse_id", WAREHOUSE);
    assertThat(
            jdbc.queryForObject(
                "select state from rental_inquiry where id=?",
                String.class,
                inquiry.id()))
        .isEqualTo("BOOKED");
    assertThat(
            jdbc.queryForObject(
                "select state from client_presentation where id=?",
                String.class,
                published.id()))
        .isEqualTo("BOOKED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from rental_inquiry_outbox where order_id=?",
                Integer.class,
                booking.orderId()))
        .isOne();

    PresentationBookingResponse replay =
        bookings.confirm(
            token, bookingKey, new ConfirmClientPresentationRequest(List.of(CABIN_1)));
    assertThat(replay.bookingId()).isEqualTo(booking.bookingId());
    assertThat(replay.orderId()).isEqualTo(booking.orderId());
    verify(dependencies, times(1))
        .convertPresentationHolds(
            eq(booking.bookingId()),
            eq(inquiry.id()),
            eq(booking.orderId()),
            eq(WAREHOUSE),
            eq(List.of(CABIN_1)),
            eq(inquiry.client().id()),
            eq("ООО Север"),
            eq(MANAGER),
            eq("RENTAL_MANAGER"));
    assertThatThrownBy(
            () ->
                bookings.confirm(
                    token,
                    UUID.randomUUID(),
                    new ConfirmClientPresentationRequest(List.of(CABIN_2))))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            failure ->
                assertThat(failure.code())
                    .isEqualTo("CLIENT_PRESENTATION_ALREADY_SUBMITTED"));
  }

  @Test
  void managerAlertsExposeTheSelectedSnapshotAndKeepTheExistingDraftOnAcknowledgement() {
    RentalInquiryResponse inquiry = createInquiry();
    ClientPresentationResponse published = publish(inquiry.id(), List.of(CABIN_1, CABIN_2));
    PresentationBookingResponse booking =
        bookings.confirm(
            token(published),
            UUID.randomUUID(),
            new ConfirmClientPresentationRequest(List.of(CABIN_1)));

    RentalBookingAlertResponse alert = bookingAlerts.list(actor).getFirst();
    assertThat(bookingAlerts.list(actor)).hasSize(1);
    assertThat(alert.bookingId()).isEqualTo(booking.bookingId());
    assertThat(alert.inquiryId()).isEqualTo(inquiry.id());
    assertThat(alert.orderId()).isEqualTo(booking.orderId());
    assertThat(alert.client().id()).isEqualTo(inquiry.client().id());
    assertThat(alert.client().displayName()).isEqualTo("ООО Север");
    assertThat(alert.confirmedAt()).isNotNull();
    assertThat(alert.cabins())
        .extracting(RentalBookingAlertCabin::id)
        .containsExactly(CABIN_1);
    assertThat(alert.cabins())
        .singleElement()
        .satisfies(
            cabin -> {
              assertThat(cabin.number()).isEqualTo("СПБ-001");
              assertThat(cabin.rentalType()).isEqualTo("БК-1");
              assertThat(cabin.dimensions()).isEqualTo("6 × 2,4");
              assertThat(cabin.finishing()).isEqualTo("ДВП");
              assertThat(cabin.category()).isEqualTo("Новая");
            });
    assertThat(orderReservations.get(booking.orderId()))
        .extracting(LogisticsDependencyGateway.OrderUnitReservation::unitId)
        .containsExactly(CABIN_1);
    assertThat(releasedCabinsByOrder.get(booking.orderId())).containsExactly(CABIN_2);

    assertThat(bookingAlerts.list(otherManager)).isEmpty();
    assertThatThrownBy(
            () ->
                bookingAlerts.act(
                    otherManager,
                    booking.bookingId(),
                    UUID.randomUUID(),
                    new RentalBookingAlertActionRequest(
                        alert.version(), PresentationBookingManagerAction.KEEP_DRAFT)))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            failure -> {
              assertThat(failure.status().value()).isEqualTo(404);
              assertThat(failure.code()).isEqualTo("PRESENTATION_BOOKING_ALERT_NOT_FOUND");
            });
    assertThatThrownBy(
            () ->
                bookingAlerts.act(
                    actor,
                    booking.bookingId(),
                    UUID.randomUUID(),
                    new RentalBookingAlertActionRequest(
                        alert.version() + 1, PresentationBookingManagerAction.KEEP_DRAFT)))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            failure ->
                assertThat(failure.code())
                    .isEqualTo("PRESENTATION_BOOKING_ALERT_VERSION_CONFLICT"));

    UUID actionKey = UUID.randomUUID();
    bookingAlerts.act(
        actor,
        booking.bookingId(),
        actionKey,
        new RentalBookingAlertActionRequest(
            alert.version(), PresentationBookingManagerAction.KEEP_DRAFT));
    long actionVersion =
        jdbc.queryForObject(
            "select version from presentation_booking where id=?", Long.class, booking.bookingId());

    assertThat(bookingAlerts.list(actor)).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select status from rental_order where id=?", String.class, booking.orderId()))
        .isEqualTo("DRAFT");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from rental_order where id=?", Integer.class, booking.orderId()))
        .isOne();
    assertThat(
            jdbc.queryForMap(
                """
                select manager_action, manager_action_idempotency_key, manager_acted_at
                from presentation_booking
                where id=?
                """,
                booking.bookingId()))
        .containsEntry("manager_action", "KEEP_DRAFT")
        .containsEntry("manager_action_idempotency_key", actionKey)
        .containsKey("manager_acted_at");

    bookingAlerts.act(
        actor,
        booking.bookingId(),
        actionKey,
        new RentalBookingAlertActionRequest(
            alert.version(), PresentationBookingManagerAction.KEEP_DRAFT));
    assertThat(
            jdbc.queryForObject(
                "select version from presentation_booking where id=?", Long.class, booking.bookingId()))
        .isEqualTo(actionVersion);
    assertThatThrownBy(
            () ->
                bookingAlerts.act(
                    actor,
                    booking.bookingId(),
                    UUID.randomUUID(),
                    new RentalBookingAlertActionRequest(
                        actionVersion, PresentationBookingManagerAction.CONTINUE)))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            failure ->
                assertThat(failure.code())
                    .isEqualTo("PRESENTATION_BOOKING_ALERT_ACTION_CONFLICT"));
  }

  @Test
  void replacingPresentationRotatesLinkAndInvalidatesPreviousRevision() {
    RentalInquiryResponse inquiry = createInquiry();
    ClientPresentationResponse first = publish(inquiry.id(), List.of(CABIN_1, CABIN_2));
    ClientPresentationResponse second = publish(inquiry.id(), List.of(CABIN_2));

    assertThat(second.id()).isEqualTo(first.id());
    assertThat(second.revision()).isEqualTo(first.revision() + 1);
    assertThat(second.publicPath()).isNotEqualTo(first.publicPath());
    assertThat(second.groups())
        .singleElement()
        .satisfies(
            group ->
                assertThat(group.cabins())
                    .extracting(PresentationCabin::id)
                    .containsExactly(CABIN_2));
    assertThatThrownBy(() -> presentations.publicPresentation(token(first)))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            failure -> assertThat(failure.status().value()).isEqualTo(410));
    assertThat(presentations.publicPresentation(token(second)).viewOnly()).isFalse();
    verify(dependencies, times(2))
        .replacePresentationHolds(
            any(), eq(inquiry.id()), eq(WAREHOUSE), anyList(), any(), eq(MANAGER), eq("RENTAL_MANAGER"));
  }

  @Test
  void bookingKeepsCompatibilityWithPresentationScopedHoldsFromExistingLinks() {
    RentalInquiryResponse inquiry = createInquiry();
    ClientPresentationResponse published = publish(inquiry.id(), List.of(CABIN_1, CABIN_2));
    LogisticsDependencyGateway.PresentationHolds inquiryScoped =
        presentationHolds.remove(inquiry.id());
    List<LogisticsDependencyGateway.PresentationHold> legacyHolds =
        inquiryScoped.holds().stream()
            .map(
                hold ->
                    new LogisticsDependencyGateway.PresentationHold(
                        hold.holdId(),
                        hold.version(),
                        published.id(),
                        hold.rentalItemId(),
                        hold.warehouseId(),
                        hold.state(),
                        hold.expiresAt(),
                        hold.orderId(),
                        hold.createdAt(),
                        hold.endedAt()))
            .toList();
    presentationHolds.put(
        published.id(),
        new LogisticsDependencyGateway.PresentationHolds(
            published.id(), inquiryScoped.expiresAt(), legacyHolds));

    PresentationBookingResponse booking =
        bookings.confirm(
            token(published),
            UUID.randomUUID(),
            new ConfirmClientPresentationRequest(List.of(CABIN_1)));

    assertThat(booking.state()).isEqualTo("COMPLETED");
    verify(dependencies)
        .convertPresentationHolds(
            eq(booking.bookingId()),
            eq(published.id()),
            eq(booking.orderId()),
            eq(WAREHOUSE),
            eq(List.of(CABIN_1)),
            eq(inquiry.client().id()),
            eq("ООО Север"),
            eq(MANAGER),
            eq("RENTAL_MANAGER"));
  }

  @Test
  void incompleteAssetConversionRejectsBookingInsteadOfFabricatingSuccess() {
    RentalInquiryResponse inquiry = createInquiry();
    ClientPresentationResponse published = publish(inquiry.id(), List.of(CABIN_1, CABIN_2));
    when(
            dependencies.convertPresentationHolds(
                any(),
                eq(inquiry.id()),
                any(),
                eq(WAREHOUSE),
                anyList(),
                any(),
                anyString(),
                eq(MANAGER),
                eq("RENTAL_MANAGER")))
        .thenAnswer(
            invocation ->
                new LogisticsDependencyGateway.ConvertedPresentationHolds(
                    inquiry.id(), invocation.getArgument(2), List.of(), List.of(CABIN_2)));

    PresentationBookingResponse rejected =
        bookings.confirm(
            token(published),
            UUID.randomUUID(),
            new ConfirmClientPresentationRequest(List.of(CABIN_1)));

    assertThat(rejected.state()).isEqualTo("REJECTED");
    assertThat(rejected.errorCode()).isEqualTo("PRESENTATION_CONVERSION_INVALID");
    assertThat(
            jdbc.queryForObject(
                "select state from client_presentation where id=?",
                String.class,
                published.id()))
        .isEqualTo("REVOKED");
  }

  @Test
  void expiredHoldBecomesViewOnlyAndTheLinkIsGoneAfterTheGraceWindow() {
    RentalInquiryResponse inquiry = createInquiry();
    ClientPresentationResponse published = publish(inquiry.id(), List.of(CABIN_1));
    String token = token(published);
    jdbc.update(
        """
        update client_presentation
        set expires_at=clock_timestamp()-interval '1 minute',
            view_until=clock_timestamp()+interval '23 hours'
        where id=?
        """,
        published.id());

    assertThat(presentations.publicPresentation(token).viewOnly()).isTrue();
    assertThatThrownBy(
            () ->
                bookings.confirm(
                    token,
                    UUID.randomUUID(),
                    new ConfirmClientPresentationRequest(List.of(CABIN_1))))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            failure ->
                assertThat(failure.code()).isEqualTo("CLIENT_PRESENTATION_VIEW_ONLY"));

    jdbc.update(
        """
        update client_presentation
        set expires_at=clock_timestamp()-interval '2 hours',
            view_until=clock_timestamp()-interval '1 hour'
        where id=?
        """,
        published.id());
    assertThatThrownBy(() -> presentations.publicPresentation(token))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            failure -> assertThat(failure.status().value()).isEqualTo(410));
  }

  private RentalInquiryResponse createInquiry() {
    UUID conversationId = UUID.randomUUID();
    return inquiries.create(
        actor,
        conversationId,
        new CreateRentalInquiryRequest(
            conversationId,
            null,
            new NewClientInput(
                ClientType.LEGAL_ENTITY, "ООО Север", "+79991234567", null)));
  }

  private ClientPresentationResponse publish(UUID inquiryId, List<UUID> ids) {
    return presentations.publish(
        actor,
        inquiryId,
        UUID.randomUUID(),
        new PublishClientPresentationRequest(
            WAREHOUSE,
            List.of(new PresentationGroupInput("bk-1", "БК-1", ids))));
  }

  private LogisticsDependencyGateway.AvailableCabin cabin(UUID id) {
    return new LogisticsDependencyGateway.AvailableCabin(
        id,
        0,
        WAREHOUSE,
        "FREE",
        id.equals(CABIN_1) ? "СПБ-001" : "СПБ-002",
        "БК-1",
        "6 × 2,4",
        "ДВП",
        id.equals(CABIN_1) ? "Новая" : "Обычная",
        "Электрика",
        true,
        Map.of("wall", "ДВП", "authorAction", "hidden"),
        List.of("Свободна"),
        now());
  }

  private LogisticsDependencyGateway.OrderUnitReservation reservation(
      UUID orderId, UUID cabinId) {
    OffsetDateTime timestamp = now();
    return new LogisticsDependencyGateway.OrderUnitReservation(
        UUID.randomUUID(),
        0,
        orderId,
        cabinId,
        WAREHOUSE,
        "ACTIVE",
        MANAGER,
        "RENTAL_MANAGER",
        timestamp,
        null,
        false,
        new LogisticsDependencyGateway.OrderRentalItem(
            cabinId,
            1,
            WAREHOUSE,
            cabinId.equals(CABIN_1) ? "СПБ-001" : "СПБ-002",
            "BOOKED",
            "БК-1",
            "6 × 2,4",
            "ДВП",
            "Обычная",
            "Электрика",
            true,
            List.of("Свободна"),
            List.of(),
            timestamp,
            timestamp));
  }

  private static String token(ClientPresentationResponse presentation) {
    return presentation.publicPath().substring("/offer/".length());
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }
}
