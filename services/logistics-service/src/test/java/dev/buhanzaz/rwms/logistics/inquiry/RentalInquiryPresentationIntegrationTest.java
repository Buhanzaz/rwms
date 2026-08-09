package dev.buhanzaz.rwms.logistics.inquiry;

import static dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.logistics.inquiry.domain.PresentationBookingManagerAction;
import dev.buhanzaz.rwms.logistics.inquiry.service.ClientPresentationService;
import dev.buhanzaz.rwms.logistics.inquiry.service.ManualBookingDraftService;
import dev.buhanzaz.rwms.logistics.inquiry.service.PresentationBookingService;
import dev.buhanzaz.rwms.logistics.inquiry.service.RentalBookingAlertService;
import dev.buhanzaz.rwms.logistics.inquiry.service.RentalInquiryCabinCatalogService;
import dev.buhanzaz.rwms.logistics.inquiry.service.RentalInquiryCabinSearchService;
import dev.buhanzaz.rwms.logistics.inquiry.service.RentalInquiryCabinSelectionService;
import dev.buhanzaz.rwms.logistics.inquiry.service.RentalInquiryService;
import dev.buhanzaz.rwms.logistics.inquiry.service.RentalSettingsService;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.NewClientInput;
import dev.buhanzaz.rwms.logistics.order.domain.ClientType;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

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
@AutoConfigureMockMvc
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
  @Autowired RentalInquiryCabinCatalogService cabinCatalog;
  @Autowired RentalInquiryCabinSearchService cabinSearches;
  @Autowired RentalInquiryCabinSelectionService cabinSelections;
  @Autowired ClientPresentationService presentations;
  @Autowired PresentationBookingService bookings;
  @Autowired RentalBookingAlertService bookingAlerts;
  @Autowired RentalSettingsService settings;
  @Autowired ManualBookingDraftService manualBookingDrafts;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired MockMvc mockMvc;
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
          rental_inquiry_selection_receipt,
          rental_inquiry_search_attempt,
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
            manual_booking_hold_minutes=60,
            presentation_hold_minutes=60,
            draft_reservation_hold_minutes=1440,
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
              return replaceHolds(presentationId, ids, expiresAt, null);
            });
    when(dependencies.replacePresentationHoldsExact(any(), any(), anyString()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              UUID presentationId = invocation.getArgument(1);
              var command = json.readTree(invocation.<String>getArgument(2));
              List<UUID> ids = new ArrayList<>();
              command
                  .get("rentalItemIds")
                  .forEach(item -> ids.add(UUID.fromString(item.stringValue())));
              return replaceHolds(
                  presentationId,
                  ids,
                  OffsetDateTime.parse(command.get("expiresAt").stringValue()),
                  null);
            });
    when(
            dependencies.replacePresentationHolds(
                any(),
                any(),
                eq(WAREHOUSE),
                anyList(),
                any(),
                eq(MANAGER),
                eq("RENTAL_MANAGER"),
                any()))
        .thenAnswer(
            invocation ->
                replaceHolds(
                    invocation.getArgument(1),
                    invocation.getArgument(3),
                    invocation.getArgument(4),
                    invocation.getArgument(7)));
    when(dependencies.readPresentationHolds(any()))
        .thenAnswer(
            invocation -> {
              UUID scopeId = invocation.getArgument(0);
              return presentationHolds.getOrDefault(
                  scopeId,
                  new LogisticsDependencyGateway.PresentationHolds(
                      scopeId, null, List.of()));
            });
    when(dependencies.readPresentationHolds(any(), eq(MANAGER), eq("RENTAL_MANAGER")))
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
    when(dependencies.releasePresentationHoldsExact(any(), any(), anyString()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              UUID scopeId = invocation.getArgument(1);
              presentationHolds.remove(scopeId);
              return new LogisticsDependencyGateway.PresentationHolds(
                  scopeId, null, List.of());
            });
  }

  @Test
  void cabinSearchUsesConfiguredHoldOwnedByTheInquiryAndExactCategory() throws Exception {
    jdbc.update(
        "update rental_settings set chat_selection_hold_minutes=17");
    RentalInquiryResponse inquiry = createInquiry();
    when(
            dependencies.searchAvailableCabins(
                any(),
                anyString()))
        .thenAnswer(
            invocation -> {
              LogisticsDependencyGateway.CabinSearchCommand command =
                  json.readValue(
                      invocation.<String>getArgument(1),
                      LogisticsDependencyGateway.CabinSearchCommand.class);
              assertThat(command.holdScopeId()).isEqualTo(inquiry.id());
              assertThat(command.actorSubjectId()).isEqualTo(MANAGER);
              assertThat(command.actorRole()).isEqualTo("RENTAL_MANAGER");
              assertThat(command.resultMode())
                  .isEqualTo(LogisticsDependencyGateway.CabinSearchResultMode.REPLACE);
              LogisticsDependencyGateway.CabinSearchGroup group =
                  command.groups().getFirst();
              assertThat(group.category()).isEqualTo("Новая");
              return new LogisticsDependencyGateway.CabinSearchResult(
                  WAREHOUSE,
                  command.expiresAt(),
                  List.of(
                      new LogisticsDependencyGateway.CabinSearchGroupResult(
                          group, List.of(cabin(CABIN_1)))));
            });
    OffsetDateTime startedAt = now();
    UUID publicKey = UUID.randomUUID();

    CabinSearchResponse result =
        cabinSearches
            .search(
                actor,
                inquiry.id(),
                publicKey,
                new CabinSearchRequest(
                    WAREHOUSE,
                    List.of(
                        new CabinSearchGroup(
                            "БК-1", "ДВП", null, "Новая", null, null, 2))))
            .response();

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
  void cabinSearchPreservesRichGroupsAndQuantitiesThroughThePublicBoundary() throws Exception {
    RentalInquiryResponse inquiry = createInquiry();
    CabinSearchGroup first =
        new CabinSearchGroup(
            "БК-1", "ДВП", "6x2.4", "Новая", "Утеплённая с электрикой", true, 6);
    CabinSearchGroup second =
        new CabinSearchGroup(
            "БК-2", "OSB", "6x2.4", "ИТР", "С дополнительной вентиляцией", false, 6);
    when(
            dependencies.searchAvailableCabins(
                any(),
                anyString()))
        .thenAnswer(
            invocation -> {
              LogisticsDependencyGateway.CabinSearchCommand command =
                  json.readValue(
                      invocation.<String>getArgument(1),
                      LogisticsDependencyGateway.CabinSearchCommand.class);
              List<LogisticsDependencyGateway.CabinSearchGroup> groups = command.groups();
              assertThat(command.resultMode())
                  .isEqualTo(LogisticsDependencyGateway.CabinSearchResultMode.APPEND);
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
                  command.expiresAt(),
                  List.of(
                      new LogisticsDependencyGateway.CabinSearchGroupResult(
                          groups.get(0), List.of(cabin(CABIN_1))),
                      new LogisticsDependencyGateway.CabinSearchGroupResult(
                          groups.get(1), List.of(cabin(CABIN_2)))));
            });

    CabinSearchResponse result =
        cabinSearches
            .search(
                actor,
                inquiry.id(),
                UUID.randomUUID(),
                new CabinSearchRequest(
                    WAREHOUSE, CabinSearchResultMode.APPEND, List.of(first, second)))
            .response();

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
  void cabinSearchLostResponseResumesExactBytesAndThenReplaysFrozenSuccess()
      throws Exception {
    RentalInquiryResponse inquiry = createInquiry();
    UUID publicKey = UUID.randomUUID();
    CabinSearchRequest request =
        new CabinSearchRequest(
            WAREHOUSE,
            List.of(
                new CabinSearchGroup(
                    "БК-1", "ДВП", null, "Новая", "Электрика", true, 2)));
    AtomicInteger remoteCalls = new AtomicInteger();
    AtomicReference<UUID> frozenDownstreamKey = new AtomicReference<>();
    AtomicReference<String> frozenBody = new AtomicReference<>();
    when(dependencies.readWarehouseIdentity(WAREHOUSE))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                  .isFalse();
              return new LogisticsDependencyGateway.WarehouseIdentity(
                  WAREHOUSE,
                  0,
                  true,
                  "Санкт-Петербург",
                  "Санкт-Петербург",
                  "Europe/Moscow");
            });
    when(dependencies.searchAvailableCabins(any(), anyString()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                  .isFalse();
              UUID downstreamKey = invocation.getArgument(0);
              String exactBody = invocation.getArgument(1);
              int call = remoteCalls.incrementAndGet();
              if (call == 1) {
                frozenDownstreamKey.set(downstreamKey);
                frozenBody.set(exactBody);
                throw new LogisticsDependencyException(
                    LogisticsDependencyException.FailureKind.TRANSIENT,
                    "Simulated lost response");
              }
              if (call == 2) {
                assertThat(downstreamKey).isEqualTo(frozenDownstreamKey.get());
                assertThat(exactBody).isEqualTo(frozenBody.get());
              } else {
                assertThat(downstreamKey).isNotEqualTo(frozenDownstreamKey.get());
              }
              LogisticsDependencyGateway.CabinSearchCommand command =
                  json.readValue(
                      exactBody,
                      LogisticsDependencyGateway.CabinSearchCommand.class);
              return new LogisticsDependencyGateway.CabinSearchResult(
                  command.warehouseId(),
                  command.expiresAt(),
                  List.of(
                      new LogisticsDependencyGateway.CabinSearchGroupResult(
                          command.groups().getFirst(), List.of(cabin(CABIN_1)))));
            });

    assertThatThrownBy(
            () -> cabinSearches.search(actor, inquiry.id(), publicKey, request))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            failure -> assertThat(failure.code()).isEqualTo("CABIN_SEARCH_UNAVAILABLE"));
    UUID expectedDownstreamKey =
        UUID.nameUUIDFromBytes(
            ("rwms:logistics:user-rental-inquiry-cabin-search:v1"
                    + '\u001f'
                    + MANAGER
                    + '\u001f'
                    + publicKey)
                .getBytes(StandardCharsets.UTF_8));
    assertThat(frozenDownstreamKey.get()).isEqualTo(expectedDownstreamKey).isNotEqualTo(publicKey);
    Map<String, Object> preparedReceipt =
        jdbc.queryForMap(
            """
            select state,downstream_idempotency_key,downstream_request_body,
              downstream_request_sha256
            from rental_inquiry_search_attempt
            where inquiry_id=?
            """,
            inquiry.id());
    assertThat(preparedReceipt)
        .containsEntry("state", "PREPARED")
        .containsEntry("downstream_idempotency_key", frozenDownstreamKey.get())
        .containsEntry("downstream_request_body", frozenBody.get());
    assertThat(preparedReceipt.get("downstream_request_sha256"))
        .isEqualTo(
            HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(frozenBody.get().getBytes(StandardCharsets.UTF_8))));
    assertThat(
            jdbc.queryForObject(
                "select warehouse_id from rental_inquiry where id=?",
                UUID.class,
                inquiry.id()))
        .isNull();
    OrderActor revokedWarehouseAuthority =
        new OrderActor(
            MANAGER,
            "RENTAL_MANAGER",
            "Менеджер без доступа к складу",
            Set.of(WAREHOUSE),
            Set.of(),
            false,
            false,
            true,
            true);
    assertThatThrownBy(
            () ->
                cabinSearches.search(
                    revokedWarehouseAuthority, inquiry.id(), publicKey, request))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(remoteCalls).hasValue(1);
    OrderActor currentElevatedAuthority =
        new OrderActor(
            MANAGER,
            "SYSTEM_ADMIN",
            "Текущий администратор",
            Set.of(),
            Set.of(),
            true,
            false,
            true,
            true);

    RentalInquiryCabinSearchService.CabinSearchOutcome resumed =
        cabinSearches.search(currentElevatedAuthority, inquiry.id(), publicKey, request);
    assertThat(resumed.replayed()).isFalse();
    assertThat(
            json.readValue(
                    frozenBody.get(), LogisticsDependencyGateway.CabinSearchCommand.class)
                .actorRole())
        .isEqualTo("RENTAL_MANAGER");
    assertThat(resumed.response().groups().getFirst().cabins())
        .extracting(AvailableCabinResponse::id)
        .containsExactly(CABIN_1);
    RentalInquiryCabinSearchService.CabinSearchOutcome replayed =
        cabinSearches.search(actor, inquiry.id(), publicKey, request);
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.response()).isEqualTo(resumed.response());
    assertThat(remoteCalls).hasValue(2);
    verify(dependencies, times(2)).readWarehouseIdentity(WAREHOUSE);

    assertThatThrownBy(
            () ->
                cabinSearches.search(
                    actor,
                    inquiry.id(),
                    publicKey,
                    new CabinSearchRequest(
                        WAREHOUSE,
                        List.of(
                            new CabinSearchGroup(
                                "БК-2", "ДВП", null, "Новая", null, null, 1)))))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            failure -> assertThat(failure.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    assertThat(remoteCalls).hasValue(2);

    RentalInquiryCabinSearchService.CabinSearchOutcome laterDistinctSearch =
        cabinSearches.search(actor, inquiry.id(), UUID.randomUUID(), request);
    assertThat(laterDistinctSearch.replayed()).isFalse();
    assertThat(remoteCalls).hasValue(3);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from rental_inquiry_search_attempt where inquiry_id=?",
                Integer.class,
                inquiry.id()))
        .isEqualTo(2);
    verify(dependencies, times(3)).readWarehouseIdentity(WAREHOUSE);
  }

  @Test
  void viewerAndReadOnlyActorsCannotCreateSearchReceiptsOrCallDependencies() {
    RentalInquiryResponse inquiry = createInquiry();
    CabinSearchRequest request =
        new CabinSearchRequest(
            WAREHOUSE,
            List.of(new CabinSearchGroup(null, null, null, null, null, null, 1)));
    OrderActor readOnly =
        new OrderActor(
            MANAGER,
            "RENTAL_MANAGER",
            "Менеджер",
            Set.of(WAREHOUSE),
            Set.of(WAREHOUSE),
            false,
            false,
            false,
            true);
    OrderActor viewer =
        new OrderActor(
            MANAGER,
            "VIEWER",
            "Наблюдатель",
            Set.of(WAREHOUSE),
            Set.of(WAREHOUSE),
            false,
            false,
            true,
            true);

    assertThatThrownBy(
            () ->
                cabinSearches.search(
                    readOnly, inquiry.id(), UUID.randomUUID(), request))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                cabinSearches.search(
                    viewer, inquiry.id(), UUID.randomUUID(), request))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from rental_inquiry_search_attempt where inquiry_id=?",
                Integer.class,
                inquiry.id()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select warehouse_id from rental_inquiry where id=?",
                UUID.class,
                inquiry.id()))
        .isNull();
    verify(dependencies, never()).readWarehouseIdentity(any());
    verify(dependencies, never()).searchAvailableCabins(any(), anyString());
  }

  @Test
  void publicSearchEndpointReturnsForbiddenForReadOnlyAndViewerTokensWithoutMutation()
      throws Exception {
    RentalInquiryResponse inquiry = createInquiry();
    String request =
        """
        {"warehouseId":"%s","groups":[{"quantity":1}]}
        """
            .formatted(WAREHOUSE);

    mockMvc
        .perform(
            post(
                    "/api/logistics/v1/rental-inquiries/{inquiryId}/cabin-searches",
                    inquiry.id())
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .subject(MANAGER.toString())
                                    .claim("principal_type", "USER")
                                    .claim("global_role", "RENTAL_MANAGER")
                                    .claim("preferred_username", "Менеджер")
                                    .claim("rentalAccess", true)
                                    .claim("scope", "rwms.read")
                                    .claim(
                                        "warehouse_access",
                                        List.of(
                                            Map.of(
                                                "warehouseId",
                                                WAREHOUSE.toString(),
                                                "level",
                                                "EDIT")))))
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post(
                    "/api/logistics/v1/rental-inquiries/{inquiryId}/cabin-searches",
                    inquiry.id())
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .subject(MANAGER.toString())
                                    .claim("principal_type", "USER")
                                    .claim("global_role", "VIEWER")
                                    .claim("preferred_username", "Наблюдатель")
                                    .claim("rentalAccess", true)
                                    .claim("scope", "rwms.read rwms.write")
                                    .claim(
                                        "warehouse_access",
                                        List.of(
                                            Map.of(
                                                "warehouseId",
                                                WAREHOUSE.toString(),
                                                "level",
                                                "EDIT")))))
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
        .andExpect(status().isForbidden());

    assertThat(
            jdbc.queryForObject(
                "select count(*) from rental_inquiry_search_attempt where inquiry_id=?",
                Integer.class,
                inquiry.id()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select warehouse_id from rental_inquiry where id=?",
                UUID.class,
                inquiry.id()))
        .isNull();
    verify(dependencies, never()).readWarehouseIdentity(any());
    verify(dependencies, never()).searchAvailableCabins(any(), anyString());
  }

  @Test
  void publicSearchEndpointMarksOnlyTheFrozenSuccessfulReplay() throws Exception {
    RentalInquiryResponse inquiry = createInquiry();
    UUID publicKey = UUID.randomUUID();
    String request =
        """
        {"warehouseId":"%s","groups":[{"cabinType":"БК-1","quantity":1}]}
        """
            .formatted(WAREHOUSE);
    when(dependencies.searchAvailableCabins(any(), anyString()))
        .thenAnswer(
            invocation -> {
              LogisticsDependencyGateway.CabinSearchCommand command =
                  json.readValue(
                      invocation.<String>getArgument(1),
                      LogisticsDependencyGateway.CabinSearchCommand.class);
              return new LogisticsDependencyGateway.CabinSearchResult(
                  command.warehouseId(),
                  command.expiresAt(),
                  List.of(
                      new LogisticsDependencyGateway.CabinSearchGroupResult(
                          command.groups().getFirst(), List.of(cabin(CABIN_1)))));
            });

    mockMvc
        .perform(
            post(
                    "/api/logistics/v1/rental-inquiries/{inquiryId}/cabin-searches",
                    inquiry.id())
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .subject(MANAGER.toString())
                                    .claim("principal_type", "USER")
                                    .claim("global_role", "RENTAL_MANAGER")
                                    .claim("preferred_username", "Менеджер")
                                    .claim("rentalAccess", true)
                                    .claim("scope", "rwms.read rwms.write")
                                    .claim(
                                        "warehouse_access",
                                        List.of(
                                            Map.of(
                                                "warehouseId",
                                                WAREHOUSE.toString(),
                                                "level",
                                                "EDIT")))))
                .header("Idempotency-Key", publicKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Idempotency-Replayed"));

    mockMvc
        .perform(
            post(
                    "/api/logistics/v1/rental-inquiries/{inquiryId}/cabin-searches",
                    inquiry.id())
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .subject(MANAGER.toString())
                                    .claim("principal_type", "USER")
                                    .claim("global_role", "RENTAL_MANAGER")
                                    .claim("preferred_username", "Менеджер")
                                    .claim("rentalAccess", true)
                                    .claim("scope", "rwms.read rwms.write")
                                    .claim(
                                        "warehouse_access",
                                        List.of(
                                            Map.of(
                                                "warehouseId",
                                                WAREHOUSE.toString(),
                                                "level",
                                                "EDIT")))))
                .header("Idempotency-Key", publicKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"));

    verify(dependencies, times(1)).readWarehouseIdentity(WAREHOUSE);
    verify(dependencies, times(1)).searchAvailableCabins(any(), anyString());
  }

  @Test
  void concurrentExactPublicKeyUsesOneReceiptAndOneWarehouseMutation() throws Exception {
    RentalInquiryResponse inquiry = createInquiry();
    UUID publicKey = UUID.randomUUID();
    CabinSearchRequest request =
        new CabinSearchRequest(
            WAREHOUSE,
            List.of(new CabinSearchGroup("БК-1", null, null, null, null, null, 1)));
    long versionBefore =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "select version from rental_inquiry where id=?", Long.class, inquiry.id()));
    CountDownLatch bothRemoteCallsEntered = new CountDownLatch(2);
    CountDownLatch releaseRemoteCalls = new CountDownLatch(1);
    List<UUID> downstreamKeys = new CopyOnWriteArrayList<>();
    List<String> exactBodies = new CopyOnWriteArrayList<>();
    when(dependencies.searchAvailableCabins(any(), anyString()))
        .thenAnswer(
            invocation -> {
              downstreamKeys.add(invocation.getArgument(0));
              String exactBody = invocation.getArgument(1);
              exactBodies.add(exactBody);
              LogisticsDependencyGateway.CabinSearchCommand command =
                  json.readValue(
                      exactBody, LogisticsDependencyGateway.CabinSearchCommand.class);
              bothRemoteCallsEntered.countDown();
              if (!releaseRemoteCalls.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release exact-key searches");
              }
              return new LogisticsDependencyGateway.CabinSearchResult(
                  command.warehouseId(),
                  command.expiresAt(),
                  List.of(
                      new LogisticsDependencyGateway.CabinSearchGroupResult(
                          command.groups().getFirst(), List.of(cabin(CABIN_1)))));
            });

    List<RentalInquiryCabinSearchService.CabinSearchOutcome> outcomes;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<RentalInquiryCabinSearchService.CabinSearchOutcome> first =
          CompletableFuture.supplyAsync(
              () -> cabinSearches.search(actor, inquiry.id(), publicKey, request), executor);
      CompletableFuture<RentalInquiryCabinSearchService.CabinSearchOutcome> second =
          CompletableFuture.supplyAsync(
              () -> cabinSearches.search(actor, inquiry.id(), publicKey, request), executor);
      assertThat(bothRemoteCallsEntered.await(10, TimeUnit.SECONDS)).isTrue();
      releaseRemoteCalls.countDown();
      outcomes =
          List.of(
              first.get(10, TimeUnit.SECONDS),
              second.get(10, TimeUnit.SECONDS));
    } finally {
      releaseRemoteCalls.countDown();
    }

    assertThat(outcomes)
        .extracting(RentalInquiryCabinSearchService.CabinSearchOutcome::replayed)
        .containsExactlyInAnyOrder(false, true);
    assertThat(outcomes.get(0).response()).isEqualTo(outcomes.get(1).response());
    assertThat(downstreamKeys).hasSize(2).allMatch(downstreamKeys.getFirst()::equals);
    assertThat(exactBodies).hasSize(2).allMatch(exactBodies.getFirst()::equals);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from rental_inquiry_search_attempt where inquiry_id=?",
                Integer.class,
                inquiry.id()))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select state from rental_inquiry_search_attempt where inquiry_id=?",
                String.class,
                inquiry.id()))
        .isEqualTo("COMPLETED");
    assertThat(
            jdbc.queryForObject(
                "select warehouse_id from rental_inquiry where id=?",
                UUID.class,
                inquiry.id()))
        .isEqualTo(WAREHOUSE);
    assertThat(
            jdbc.queryForObject(
                "select version from rental_inquiry where id=?", Long.class, inquiry.id()))
        .isEqualTo(versionBefore + 1);
    verify(dependencies, times(2)).readWarehouseIdentity(WAREHOUSE);
    verify(dependencies, times(2)).searchAvailableCabins(any(), anyString());
  }

  @Test
  void aDistinctSearchConflictsWhileAnotherPreparedReceiptIsRemotelyInFlight()
      throws Exception {
    RentalInquiryResponse inquiry = createInquiry();
    CabinSearchRequest request =
        new CabinSearchRequest(
            WAREHOUSE,
            List.of(new CabinSearchGroup("БК-1", null, null, null, null, null, 1)));
    CountDownLatch remoteEntered = new CountDownLatch(1);
    CountDownLatch releaseRemote = new CountDownLatch(1);
    when(dependencies.searchAvailableCabins(any(), anyString()))
        .thenAnswer(
            invocation -> {
              LogisticsDependencyGateway.CabinSearchCommand command =
                  json.readValue(
                      invocation.<String>getArgument(1),
                      LogisticsDependencyGateway.CabinSearchCommand.class);
              remoteEntered.countDown();
              if (!releaseRemote.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release the remote search");
              }
              return new LogisticsDependencyGateway.CabinSearchResult(
                  command.warehouseId(),
                  command.expiresAt(),
                  List.of(
                      new LogisticsDependencyGateway.CabinSearchGroupResult(
                          command.groups().getFirst(), List.of(cabin(CABIN_1)))));
            });

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<RentalInquiryCabinSearchService.CabinSearchOutcome> first =
          CompletableFuture.supplyAsync(
              () ->
                  cabinSearches.search(
                      actor, inquiry.id(), UUID.randomUUID(), request),
              executor);
      assertThat(remoteEntered.await(10, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(
              () ->
                  cabinSearches.search(
                      actor, inquiry.id(), UUID.randomUUID(), request))
          .isInstanceOfSatisfying(
              OrderProblemException.class,
              failure -> assertThat(failure.code()).isEqualTo("CABIN_SEARCH_IN_PROGRESS"));
      releaseRemote.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS).response().warehouseId())
          .isEqualTo(WAREHOUSE);
    } finally {
      releaseRemote.countDown();
    }
    verify(dependencies, times(1)).searchAvailableCabins(any(), anyString());
  }

  @Test
  void inactiveWarehouseIsSanitizedAsRejectedWithoutCallingAssetSearch() {
    RentalInquiryResponse inquiry = createInquiry();
    UUID publicKey = UUID.randomUUID();
    CabinSearchRequest request =
        new CabinSearchRequest(
            WAREHOUSE,
            List.of(new CabinSearchGroup(null, null, null, null, null, null, 1)));
    when(dependencies.readWarehouseIdentity(WAREHOUSE))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseIdentity(
                WAREHOUSE,
                3,
                false,
                "Санкт-Петербург",
                "Санкт-Петербург",
                "Europe/Moscow"));

    assertThatThrownBy(
            () -> cabinSearches.search(actor, inquiry.id(), publicKey, request))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            failure -> assertThat(failure.code()).isEqualTo("WAREHOUSE_UNAVAILABLE"));
    assertThat(
            jdbc.queryForMap(
                """
                select state,rejection_code,response_body
                from rental_inquiry_search_attempt where inquiry_id=?
                """,
                inquiry.id()))
        .containsEntry("state", "REJECTED")
        .containsEntry("rejection_code", "WAREHOUSE_UNAVAILABLE")
        .containsEntry("response_body", null);
    assertThatThrownBy(
            () -> cabinSearches.search(actor, inquiry.id(), publicKey, request))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            failure -> assertThat(failure.code()).isEqualTo("WAREHOUSE_UNAVAILABLE"));
    verify(dependencies, times(1)).readWarehouseIdentity(WAREHOUSE);
    verify(dependencies, never()).searchAvailableCabins(any(), anyString());
  }

  @Test
  void classifiedAssetConflictIsSanitizedAndFrozenAsRejected() {
    RentalInquiryResponse inquiry = createInquiry();
    UUID publicKey = UUID.randomUUID();
    CabinSearchRequest request =
        new CabinSearchRequest(
            WAREHOUSE,
            List.of(new CabinSearchGroup("БК-1", null, null, null, null, null, 1)));
    when(dependencies.searchAvailableCabins(any(), anyString()))
        .thenThrow(
            new LogisticsDependencyException(
                LogisticsDependencyException.FailureKind.PERMANENT_REJECTION,
                "UNIT_PRESENTATION_HELD",
                "Sanitized asset conflict",
                new IllegalStateException("Unpersisted remote Problem body")));

    assertThatThrownBy(
            () -> cabinSearches.search(actor, inquiry.id(), publicKey, request))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            failure -> assertThat(failure.code()).isEqualTo("UNIT_PRESENTATION_HELD"));
    assertThat(
            jdbc.queryForMap(
                """
                select state,rejection_code,response_body
                from rental_inquiry_search_attempt where inquiry_id=?
                """,
                inquiry.id()))
        .containsEntry("state", "REJECTED")
        .containsEntry("rejection_code", "UNIT_PRESENTATION_HELD")
        .containsEntry("response_body", null);
    assertThat(
            jdbc.queryForObject(
                "select warehouse_id from rental_inquiry where id=?",
                UUID.class,
                inquiry.id()))
        .isNull();

    assertThatThrownBy(
            () -> cabinSearches.search(actor, inquiry.id(), publicKey, request))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            failure -> assertThat(failure.code()).isEqualTo("UNIT_PRESENTATION_HELD"));
    verify(dependencies, times(1)).readWarehouseIdentity(WAREHOUSE);
    verify(dependencies, times(1)).searchAvailableCabins(any(), anyString());
  }

  @Test
  void expiredPreparedReceiptReleasesTheInquirySlotButItsOwnKeyStaysTerminal()
      throws Exception {
    RentalInquiryResponse inquiry = createInquiry();
    UUID expiredKey = UUID.randomUUID();
    CabinSearchRequest request =
        new CabinSearchRequest(
            WAREHOUSE,
            List.of(new CabinSearchGroup("БК-1", null, null, null, null, null, 1)));
    when(dependencies.searchAvailableCabins(any(), anyString()))
        .thenThrow(
            new LogisticsDependencyException(
                LogisticsDependencyException.FailureKind.TRANSIENT,
                "Simulated unknown response"));

    assertThatThrownBy(
            () -> cabinSearches.search(actor, inquiry.id(), expiredKey, request))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            failure -> assertThat(failure.code()).isEqualTo("CABIN_SEARCH_UNAVAILABLE"));
    assertThatThrownBy(
            () ->
                cabinSearches.search(
                    actor, inquiry.id(), UUID.randomUUID(), request))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            failure -> assertThat(failure.code()).isEqualTo("CABIN_SEARCH_IN_PROGRESS"));
    jdbc.update(
        """
        update rental_inquiry_search_attempt
        set created_at=clock_timestamp() - interval '20 minutes',
            hold_expires_at=clock_timestamp() - interval '10 minutes',
            updated_at=clock_timestamp()
        where inquiry_id=? and state='PREPARED'
        """,
        inquiry.id());

    assertThatThrownBy(
            () -> cabinSearches.search(actor, inquiry.id(), expiredKey, request))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            failure -> assertThat(failure.code()).isEqualTo("CABIN_SEARCH_EXPIRED"));
    assertThat(
            jdbc.queryForObject(
                "select state from rental_inquiry_search_attempt where inquiry_id=?",
                String.class,
                inquiry.id()))
        .isEqualTo("EXPIRED");

    doAnswer(
            invocation -> {
              LogisticsDependencyGateway.CabinSearchCommand command =
                  json.readValue(
                      invocation.<String>getArgument(1),
                      LogisticsDependencyGateway.CabinSearchCommand.class);
              return new LogisticsDependencyGateway.CabinSearchResult(
                  command.warehouseId(),
                  command.expiresAt(),
                  List.of(
                      new LogisticsDependencyGateway.CabinSearchGroupResult(
                          command.groups().getFirst(), List.of(cabin(CABIN_1)))));
            })
        .when(dependencies)
        .searchAvailableCabins(any(), anyString());
    RentalInquiryCabinSearchService.CabinSearchOutcome replacement =
        cabinSearches.search(actor, inquiry.id(), UUID.randomUUID(), request);
    assertThat(replacement.response().warehouseId()).isEqualTo(WAREHOUSE);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from rental_inquiry_search_attempt where inquiry_id=?",
                Integer.class,
                inquiry.id()))
        .isEqualTo(2);
    verify(dependencies, times(2)).searchAvailableCabins(any(), anyString());
  }

  @Test
  void cabinFacetsUsesTheInquiryAsTheAssetHoldScope() {
    RentalInquiryResponse inquiry = createInquiry();
    when(dependencies.readAvailableCabinFacets(WAREHOUSE, inquiry.id()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return new LogisticsDependencyGateway.CabinFacets(
                  WAREHOUSE,
                  List.of("БК-1"),
                  List.of("ДВП"),
                  List.of("6x2.4"),
                  List.of("Новая"),
                  List.of("Утеплённая", "Электрика"),
                  List.of(
                      new LogisticsDependencyGateway.CabinTypeDimensionRelation(
                          "БК-1", List.of("6x2.4"))));
            });

    CabinFacetsResponse result = inquiries.facets(actor, inquiry.id());

    assertThat(result.warehouses())
        .singleElement()
        .satisfies(
            warehouse -> {
              assertThat(warehouse.warehouseId()).isEqualTo(WAREHOUSE);
              assertThat(warehouse.cabinTypes()).containsExactly("БК-1");
              assertThat(warehouse.characteristics()).containsExactly("Утеплённая", "Электрика");
              assertThat(warehouse.typeDimensions())
                  .containsExactly(new CabinTypeDimensionRelation("БК-1", List.of("6x2.4")));
            });
    verify(dependencies).readAvailableCabinFacets(WAREHOUSE, inquiry.id());
  }

  @Test
  void authoritativeSelectionReplacesResetsAndReleasesAssetOwnedHoldsOutsideTransactions() {
    RentalInquiryResponse inquiry = createInquiry();
    jdbc.update("update rental_inquiry set warehouse_id=? where id=?", WAREHOUSE, inquiry.id());

    OffsetDateTime startedAt = now();
    CabinSelectionResponse first =
        cabinSelections.replace(
            actor,
            inquiry.id(),
            UUID.randomUUID(),
            new CabinSelectionRequest(WAREHOUSE, List.of(CABIN_1, CABIN_2)));
    assertThat(first.rentalItemIds()).containsExactly(CABIN_1, CABIN_2);
    assertThat(first.items())
        .extracting(AvailableCabinResponse::id)
        .containsExactly(CABIN_1, CABIN_2);
    assertThat(first.expiresAt())
        .isAfter(startedAt.plusMinutes(9))
        .isBefore(startedAt.plusMinutes(11));
    CabinSelectionResponse current = cabinSelections.get(actor, inquiry.id());
    assertThat(current.rentalItemIds()).isEqualTo(first.rentalItemIds());
    assertThat(current.expiresAt()).isEqualTo(first.expiresAt());

    CabinSelectionResponse replaced =
        cabinSelections.replace(
            actor,
            inquiry.id(),
            UUID.randomUUID(),
            new CabinSelectionRequest(WAREHOUSE, List.of(CABIN_2)));
    assertThat(replaced.rentalItemIds()).containsExactly(CABIN_2);
    assertThat(replaced.expiresAt()).isAfterOrEqualTo(first.expiresAt());
    assertThat(presentationHolds.get(inquiry.id()).holds())
        .extracting(LogisticsDependencyGateway.PresentationHold::rentalItemId)
        .containsExactly(CABIN_2);

    CabinSelectionResponse released =
        cabinSelections.replace(
            actor,
            inquiry.id(),
            UUID.randomUUID(),
            new CabinSelectionRequest(WAREHOUSE, List.of()));
    assertThat(released.expiresAt()).isNull();
    assertThat(released.rentalItemIds()).isEmpty();
    assertThat(released.items()).isEmpty();
    assertThat(presentationHolds).doesNotContainKey(inquiry.id());
    verify(dependencies, times(2))
        .replacePresentationHoldsExact(any(), eq(inquiry.id()), anyString());
    verify(dependencies).releasePresentationHoldsExact(any(), eq(inquiry.id()), anyString());
  }

  @Test
  void selectionLostResponseRetriesFrozenBytesThenReplaysAndRejectsChangedReuse() {
    RentalInquiryResponse inquiry = createInquiry();
    jdbc.update("update rental_inquiry set warehouse_id=? where id=?", WAREHOUSE, inquiry.id());
    UUID publicKey = UUID.randomUUID();
    AtomicInteger remoteCalls = new AtomicInteger();
    List<String> exactBodies = new CopyOnWriteArrayList<>();
    doAnswer(
            invocation -> {
              String exactBody = invocation.getArgument(2);
              exactBodies.add(exactBody);
              var command = json.readTree(exactBody);
              List<UUID> ids = new ArrayList<>();
              command
                  .get("rentalItemIds")
                  .forEach(item -> ids.add(UUID.fromString(item.stringValue())));
              LogisticsDependencyGateway.PresentationHolds response =
                  replaceHolds(
                      inquiry.id(),
                      ids,
                      OffsetDateTime.parse(command.get("expiresAt").stringValue()),
                      null);
              if (remoteCalls.getAndIncrement() == 0) {
                throw new LogisticsDependencyException(
                    LogisticsDependencyException.FailureKind.TRANSIENT,
                    "Simulated lost selection response");
              }
              return response;
            })
        .when(dependencies)
        .replacePresentationHoldsExact(eq(publicKey), eq(inquiry.id()), anyString());

    CabinSelectionRequest request = new CabinSelectionRequest(WAREHOUSE, List.of(CABIN_1, CABIN_2));
    assertThatThrownBy(() -> cabinSelections.replace(actor, inquiry.id(), publicKey, request))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> assertThat(problem.code()).isEqualTo("CABIN_SELECTION_UNAVAILABLE"));
    assertThat(
            jdbc.queryForObject(
                "select state from rental_inquiry_selection_receipt where public_idempotency_key=?",
                String.class,
                publicKey))
        .isEqualTo("PREPARED");

    CabinSelectionResponse resumed =
        cabinSelections.replace(actor, inquiry.id(), publicKey, request);
    CabinSelectionResponse replayed =
        cabinSelections.replace(actor, inquiry.id(), publicKey, request);

    assertThat(resumed).isEqualTo(replayed);
    assertThat(exactBodies)
        .hasSize(2)
        .allSatisfy(body -> assertThat(body).isEqualTo(exactBodies.getFirst()));
    assertThat(
            jdbc.queryForObject(
                "select state from rental_inquiry_selection_receipt where public_idempotency_key=?",
                String.class,
                publicKey))
        .isEqualTo("COMPLETED");
    verify(dependencies, times(2))
        .replacePresentationHoldsExact(eq(publicKey), eq(inquiry.id()), anyString());

    assertThatThrownBy(
            () ->
                cabinSelections.replace(
                    actor,
                    inquiry.id(),
                    publicKey,
                    new CabinSelectionRequest(WAREHOUSE, List.of(CABIN_2))))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> assertThat(problem.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
  }

  @Test
  void emptySelectionReleaseAlsoRetriesFrozenBytesAfterALostResponse() {
    RentalInquiryResponse inquiry = createInquiry();
    jdbc.update("update rental_inquiry set warehouse_id=? where id=?", WAREHOUSE, inquiry.id());
    presentationHolds.put(
        inquiry.id(), replaceHolds(inquiry.id(), List.of(CABIN_1), now().plusMinutes(10), null));
    UUID publicKey = UUID.randomUUID();
    AtomicInteger remoteCalls = new AtomicInteger();
    List<String> exactBodies = new CopyOnWriteArrayList<>();
    when(dependencies.releasePresentationHoldsExact(eq(publicKey), eq(inquiry.id()), anyString()))
        .thenAnswer(
            invocation -> {
              String exactBody = invocation.getArgument(2);
              exactBodies.add(exactBody);
              var command = json.readTree(exactBody);
              assertThat(command.get("actorSubjectId").stringValue()).isEqualTo(MANAGER.toString());
              assertThat(command.get("actorRole").stringValue()).isEqualTo("RENTAL_MANAGER");
              presentationHolds.remove(inquiry.id());
              LogisticsDependencyGateway.PresentationHolds response =
                  new LogisticsDependencyGateway.PresentationHolds(inquiry.id(), null, List.of());
              if (remoteCalls.getAndIncrement() == 0) {
                throw new LogisticsDependencyException(
                    LogisticsDependencyException.FailureKind.TRANSIENT,
                    "Simulated lost release response");
              }
              return response;
            });
    CabinSelectionRequest request = new CabinSelectionRequest(WAREHOUSE, List.of());

    assertThatThrownBy(() -> cabinSelections.replace(actor, inquiry.id(), publicKey, request))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> assertThat(problem.code()).isEqualTo("CABIN_SELECTION_UNAVAILABLE"));
    assertThat(
            jdbc.queryForMap(
                """
                select command_type,command_expires_at,hold_expires_at,state
                from rental_inquiry_selection_receipt
                where public_idempotency_key=?
                """,
                publicKey))
        .containsEntry("command_type", "RELEASE")
        .containsEntry("hold_expires_at", null)
        .containsEntry("state", "PREPARED")
        .satisfies(row -> assertThat(row.get("command_expires_at")).isNotNull());

    CabinSelectionResponse resumed =
        cabinSelections.replace(actor, inquiry.id(), publicKey, request);
    CabinSelectionResponse replayed =
        cabinSelections.replace(actor, inquiry.id(), publicKey, request);

    assertThat(resumed).isEqualTo(replayed);
    assertThat(resumed.rentalItemIds()).isEmpty();
    assertThat(resumed.expiresAt()).isNull();
    assertThat(exactBodies)
        .hasSize(2)
        .allSatisfy(body -> assertThat(body).isEqualTo(exactBodies.getFirst()));
    verify(dependencies, times(2))
        .releasePresentationHoldsExact(eq(publicKey), eq(inquiry.id()), anyString());

    CabinSelectionResponse nextSelection =
        cabinSelections.replace(
            actor,
            inquiry.id(),
            UUID.randomUUID(),
            new CabinSelectionRequest(WAREHOUSE, List.of(CABIN_2)));
    assertThat(nextSelection.rentalItemIds()).containsExactly(CABIN_2);
  }

  @Test
  void unknownReleaseExpiresFromItsFrozenCommandDeadlineAndReleasesTheInquirySlot() {
    RentalInquiryResponse inquiry = createInquiry();
    jdbc.update("update rental_inquiry set warehouse_id=? where id=?", WAREHOUSE, inquiry.id());
    UUID publicKey = UUID.randomUUID();
    List<String> exactBodies = new CopyOnWriteArrayList<>();
    when(dependencies.releasePresentationHoldsExact(eq(publicKey), eq(inquiry.id()), anyString()))
        .thenAnswer(
            invocation -> {
              exactBodies.add(invocation.getArgument(2));
              throw new LogisticsDependencyException(
                  LogisticsDependencyException.FailureKind.TRANSIENT,
                  "Simulated unknown release outcome");
            });
    CabinSelectionRequest release = new CabinSelectionRequest(WAREHOUSE, List.of());

    for (int attempt = 0; attempt < 2; attempt++) {
      assertThatThrownBy(() -> cabinSelections.replace(actor, inquiry.id(), publicKey, release))
          .isInstanceOfSatisfying(
              OrderProblemException.class,
              problem -> assertThat(problem.code()).isEqualTo("CABIN_SELECTION_UNAVAILABLE"));
    }
    assertThat(exactBodies)
        .hasSize(2)
        .allSatisfy(body -> assertThat(body).isEqualTo(exactBodies.getFirst()));
    jdbc.update(
        """
        update rental_inquiry_selection_receipt
        set created_at=clock_timestamp() - interval '2 minutes',
            updated_at=clock_timestamp() - interval '2 minutes',
            command_expires_at=clock_timestamp() - interval '1 minute'
        where public_idempotency_key=?
        """,
        publicKey);

    assertThatThrownBy(() -> cabinSelections.replace(actor, inquiry.id(), publicKey, release))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> assertThat(problem.code()).isEqualTo("CABIN_SELECTION_RECEIPT_EXPIRED"));
    assertThat(
            jdbc.queryForObject(
                "select state from rental_inquiry_selection_receipt where public_idempotency_key=?",
                String.class,
                publicKey))
        .isEqualTo("EXPIRED");
    verify(dependencies, times(2))
        .releasePresentationHoldsExact(eq(publicKey), eq(inquiry.id()), anyString());

    CabinSelectionResponse replacement =
        cabinSelections.replace(
            actor,
            inquiry.id(),
            UUID.randomUUID(),
            new CabinSelectionRequest(WAREHOUSE, List.of(CABIN_1)));
    assertThat(replacement.rentalItemIds()).containsExactly(CABIN_1);
  }

  @Test
  void concurrentDistinctSelectionKeyIsRejectedWhileThePreparedEffectIsInFlight() throws Exception {
    RentalInquiryResponse inquiry = createInquiry();
    jdbc.update("update rental_inquiry set warehouse_id=? where id=?", WAREHOUSE, inquiry.id());
    UUID firstKey = UUID.randomUUID();
    CountDownLatch remoteEntered = new CountDownLatch(1);
    CountDownLatch releaseRemote = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              String exactBody = invocation.getArgument(2);
              var command = json.readTree(exactBody);
              remoteEntered.countDown();
              if (!releaseRemote.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release the selection effect");
              }
              return replaceHolds(
                  inquiry.id(),
                  List.of(CABIN_1),
                  OffsetDateTime.parse(command.get("expiresAt").stringValue()),
                  null);
            })
        .when(dependencies)
        .replacePresentationHoldsExact(eq(firstKey), eq(inquiry.id()), anyString());

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<CabinSelectionResponse> first =
          CompletableFuture.supplyAsync(
              () ->
                  cabinSelections.replace(
                      actor,
                      inquiry.id(),
                      firstKey,
                      new CabinSelectionRequest(WAREHOUSE, List.of(CABIN_1))),
              executor);
      assertThat(remoteEntered.await(10, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(
              () ->
                  cabinSelections.replace(
                      actor,
                      inquiry.id(),
                      UUID.randomUUID(),
                      new CabinSelectionRequest(WAREHOUSE, List.of(CABIN_2))))
          .isInstanceOfSatisfying(
              OrderProblemException.class,
              problem -> assertThat(problem.code()).isEqualTo("CABIN_SELECTION_IN_PROGRESS"));
      releaseRemote.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS).rentalItemIds()).containsExactly(CABIN_1);
    } finally {
      releaseRemote.countDown();
    }
    verify(dependencies, times(1))
        .replacePresentationHoldsExact(eq(firstKey), eq(inquiry.id()), anyString());
  }

  @Test
  void selectionIsOwnerScopedAndFreshInquiryHasAnEmptyNullableWarehouse() {
    RentalInquiryResponse inquiry = createInquiry();

    CabinSelectionResponse empty = cabinSelections.get(actor, inquiry.id());
    assertThat(empty.warehouseId()).isNull();
    assertThat(empty.expiresAt()).isNull();
    assertThat(empty.rentalItemIds()).isEmpty();
    assertThatThrownBy(() -> cabinSelections.get(otherManager, inquiry.id()))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> {
              assertThat(problem.status().value()).isEqualTo(404);
              assertThat(problem.code()).isEqualTo("INQUIRY_NOT_FOUND");
            });
    verify(dependencies, never()).readPresentationHolds(any(), any(), anyString());
  }

  @Test
  void activeSelectionHoldWithoutCabinIdentityBecomesSanitizedDependencyUnavailable() {
    RentalInquiryResponse inquiry = createInquiry();
    jdbc.update("update rental_inquiry set warehouse_id=? where id=?", WAREHOUSE, inquiry.id());
    when(dependencies.readPresentationHolds(inquiry.id(), MANAGER, "RENTAL_MANAGER"))
        .thenReturn(
            new LogisticsDependencyGateway.PresentationHolds(
                inquiry.id(),
                now().plusMinutes(10),
                List.of(
                    new LogisticsDependencyGateway.PresentationHold(
                        UUID.randomUUID(),
                        0,
                        inquiry.id(),
                        null,
                        WAREHOUSE,
                        "ACTIVE",
                        now().plusMinutes(10),
                        null,
                        now(),
                        null))));

    assertThatThrownBy(() -> cabinSelections.get(actor, inquiry.id()))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> {
              assertThat(problem.status().value()).isEqualTo(503);
              assertThat(problem.code()).isEqualTo("CABIN_SELECTION_INVALID_RESPONSE");
              assertThat(problem.getMessage()).doesNotContain("asset", "UUID");
            });
    verify(dependencies, never()).readCabinSnapshots(any(), anyList());
  }

  @Test
  void selectionRejectsANonFreeOrIncompleteCabinSnapshot() {
    RentalInquiryResponse inquiry = createInquiry();
    jdbc.update("update rental_inquiry set warehouse_id=? where id=?", WAREHOUSE, inquiry.id());
    OffsetDateTime expiry = now().plusMinutes(10);
    presentationHolds.put(inquiry.id(), replaceHolds(inquiry.id(), List.of(CABIN_1), expiry, null));
    LogisticsDependencyGateway.AvailableCabin source = cabin(CABIN_1);
    when(dependencies.readCabinSnapshots(WAREHOUSE, List.of(CABIN_1)))
        .thenReturn(
            List.of(
                new LogisticsDependencyGateway.AvailableCabin(
                    source.id(),
                    source.version(),
                    source.warehouseId(),
                    "RENTED",
                    source.number(),
                    source.rentalType(),
                    source.dimensions(),
                    source.finishing(),
                    source.category(),
                    source.characteristics(),
                    source.linoleum(),
                    source.passport(),
                    source.tags(),
                    source.updatedAt())));

    assertThatThrownBy(() -> cabinSelections.get(actor, inquiry.id()))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> {
              assertThat(problem.status().value()).isEqualTo(503);
              assertThat(problem.code()).isEqualTo("CABIN_SELECTION_INVALID_RESPONSE");
            });

    List<String> invalidTags = new ArrayList<>();
    invalidTags.add(null);
    when(dependencies.readCabinSnapshots(WAREHOUSE, List.of(CABIN_1)))
        .thenReturn(
            List.of(
                new LogisticsDependencyGateway.AvailableCabin(
                    source.id(),
                    source.version(),
                    source.warehouseId(),
                    "FREE",
                    source.number(),
                    source.rentalType(),
                    source.dimensions(),
                    source.finishing(),
                    source.category(),
                    source.characteristics(),
                    source.linoleum(),
                    source.passport(),
                    invalidTags,
                    source.updatedAt())));

    assertThatThrownBy(() -> cabinSelections.get(actor, inquiry.id()))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> assertThat(problem.code()).isEqualTo("CABIN_SELECTION_INVALID_RESPONSE"));
  }

  @Test
  void cabinCatalogIsPagedFactsOnlyAndRunsOutsideTheInquiryReadTransaction() {
    RentalInquiryResponse inquiry = createInquiry();
    LogisticsDependencyGateway.AvailableCabin rented =
        new LogisticsDependencyGateway.AvailableCabin(
            CABIN_1,
            7,
            WAREHOUSE,
            "RENTED",
            "СПБ-001",
            "БК-1",
            "6 × 2,4",
            "ДВП",
            "Новая",
            "Электрика",
            true,
            Map.of("wall", "ДВП"),
            List.of("Арендована"),
            now());
    when(dependencies.readCabinCatalog(WAREHOUSE, "СПБ-001", 0, 20))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return new LogisticsDependencyGateway.CabinCatalogPage(
                  WAREHOUSE, List.of(rented), 0, 20, 1, 1);
            });

    CabinCatalogResponse result =
        cabinCatalog.search(actor, inquiry.id(), WAREHOUSE, " СПБ-001 ", 0, 20);

    assertThat(result.warehouseId()).isEqualTo(WAREHOUSE);
    assertThat(result.content())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.id()).isEqualTo(CABIN_1);
              assertThat(item.status()).isEqualTo("RENTED");
            });
    assertThat(result.totalElements()).isOne();
    assertThatThrownBy(
            () -> cabinCatalog.search(otherManager, inquiry.id(), WAREHOUSE, "СПБ-001", 0, 20))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> assertThat(problem.status().value()).isEqualTo(404));
    verify(dependencies, times(1)).readCabinCatalog(WAREHOUSE, "СПБ-001", 0, 20);
    verify(dependencies, never())
        .replacePresentationHolds(
            any(), any(), any(), anyList(), any(), any(), anyString());
    verify(dependencies, never()).releasePresentationHolds(any(), any(), any(), anyString());
    verify(dependencies, never()).replacePresentationHoldsExact(any(), any(), anyString());
    verify(dependencies, never()).releasePresentationHoldsExact(any(), any(), anyString());
  }

  @Test
  void malformedCabinCatalogPagesBecomeSanitizedDependencyUnavailable() {
    RentalInquiryResponse inquiry = createInquiry();
    when(dependencies.readCabinCatalog(WAREHOUSE, "СПБ", 0, 20))
        .thenReturn(
            new LogisticsDependencyGateway.CabinCatalogPage(
                UUID.randomUUID(), List.of(cabin(CABIN_1)), 0, 20, 1, 1));

    assertMalformedCatalog(inquiry.id());

    LogisticsDependencyGateway.AvailableCabin duplicate = cabin(CABIN_1);
    when(dependencies.readCabinCatalog(WAREHOUSE, "СПБ", 0, 20))
        .thenReturn(
            new LogisticsDependencyGateway.CabinCatalogPage(
                WAREHOUSE, List.of(duplicate, duplicate), 0, 20, 2, 1));

    assertMalformedCatalog(inquiry.id());

    when(dependencies.readCabinCatalog(WAREHOUSE, "СПБ", 0, 20))
        .thenReturn(
            new LogisticsDependencyGateway.CabinCatalogPage(
                WAREHOUSE, List.of(cabin(null)), 0, 20, 1, 1));

    assertMalformedCatalog(inquiry.id());

    when(dependencies.readCabinCatalog(WAREHOUSE, "СПБ", 0, 20))
        .thenReturn(
            new LogisticsDependencyGateway.CabinCatalogPage(
                WAREHOUSE, List.of(cabin(CABIN_1)), 0, 20, 21, 1));

    assertMalformedCatalog(inquiry.id());
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
            new UpdateRentalSettingsRequest(initial.version(), 12, 75, 90, 2_880));

    assertThat(updated.version()).isEqualTo(initial.version() + 1);
    assertThat(updated.chatSelectionHoldMinutes()).isEqualTo(12);
    assertThat(updated.manualBookingHoldMinutes()).isEqualTo(75);
    assertThat(updated.presentationHoldMinutes()).isEqualTo(90);
    assertThat(updated.draftReservationHoldMinutes()).isEqualTo(2_880);
    assertThat(updated.updatedBy()).isEqualTo(MANAGER);
  }

  @Test
  void manualBookingDraftUsesItsOwnConfiguredHoldDuration() {
    jdbc.update("update rental_settings set manual_booking_hold_minutes=45");
    UUID draftId = UUID.randomUUID();
    OffsetDateTime startedAt = now();

    ManualBookingDraftHoldsResponse held =
        manualBookingDrafts.replace(
            actor,
            UUID.randomUUID(),
            draftId,
            new ManualBookingDraftHoldsRequest(
                WAREHOUSE, List.of(CABIN_1, CABIN_2)));

    assertThat(held.draftId()).isEqualTo(draftId);
    assertThat(held.warehouseId()).isEqualTo(WAREHOUSE);
    assertThat(held.rentalItemIds()).containsExactly(CABIN_1, CABIN_2);
    assertThat(held.expiresAt())
        .isAfter(startedAt.plusMinutes(44))
        .isBefore(startedAt.plusMinutes(46));
    assertThat(manualBookingDrafts.get(actor, draftId, WAREHOUSE))
        .isEqualTo(held);
  }

  @Test
  void publishingManualBookingTransfersItsDraftHoldScope() {
    UUID draftId = UUID.randomUUID();
    manualBookingDrafts.replace(
        actor,
        UUID.randomUUID(),
        draftId,
        new ManualBookingDraftHoldsRequest(
            WAREHOUSE, List.of(CABIN_1, CABIN_2)));
    RentalInquiryResponse inquiry = createInquiry();

    ClientPresentationResponse published =
        presentations.publish(
            actor,
            inquiry.id(),
            UUID.randomUUID(),
            new PublishClientPresentationRequest(
                WAREHOUSE,
                List.of(
                    new PresentationGroupInput(
                        "bk-1", "БК-1", List.of(CABIN_1))),
                draftId));

    assertThat(published.groups())
        .singleElement()
        .satisfies(
            group ->
                assertThat(group.cabins())
                    .extracting(PresentationCabin::id)
                    .containsExactly(CABIN_1));
    assertThat(presentationHolds).doesNotContainKey(draftId);
    assertThat(presentationHolds.get(inquiry.id()).holds())
        .extracting(LogisticsDependencyGateway.PresentationHold::rentalItemId)
        .containsExactly(CABIN_1);
    verify(dependencies)
        .replacePresentationHolds(
            any(),
            eq(inquiry.id()),
            eq(WAREHOUSE),
            eq(List.of(CABIN_1)),
            any(),
            eq(MANAGER),
            eq("RENTAL_MANAGER"),
            eq(draftId));
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
                "select version from presentation_booking where id=?",
                Long.class,
                booking.bookingId()))
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
            any(),
            eq(inquiry.id()),
            eq(WAREHOUSE),
            anyList(),
            any(),
            eq(MANAGER),
            eq("RENTAL_MANAGER"),
            eq(null));
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
                ClientType.LEGAL_ENTITY,
                "ООО Север",
                "+79991234567",
                "Анна Северова",
                null,
                null,
                null)));
  }

  private ClientPresentationResponse publish(UUID inquiryId, List<UUID> ids) {
    return presentations.publish(
        actor,
        inquiryId,
        UUID.randomUUID(),
        new PublishClientPresentationRequest(
            WAREHOUSE,
            List.of(new PresentationGroupInput("bk-1", "БК-1", ids)),
            null));
  }

  private LogisticsDependencyGateway.AvailableCabin cabin(UUID id) {
    return new LogisticsDependencyGateway.AvailableCabin(
        id,
        0,
        WAREHOUSE,
        "FREE",
        CABIN_1.equals(id) ? "СПБ-001" : "СПБ-002",
        "БК-1",
        "6 × 2,4",
        "ДВП",
        CABIN_1.equals(id) ? "Новая" : "Обычная",
        "Электрика",
        true,
        Map.of("wall", "ДВП", "authorAction", "hidden"),
        List.of("Свободна"),
        now());
  }

  private LogisticsDependencyGateway.PresentationHolds replaceHolds(
      UUID presentationId,
      List<UUID> ids,
      OffsetDateTime expiresAt,
      UUID sourceHoldScopeId) {
    if (sourceHoldScopeId != null) presentationHolds.remove(sourceHoldScopeId);
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

  private void assertMalformedCatalog(UUID inquiryId) {
    assertThatThrownBy(
            () -> cabinCatalog.search(actor, inquiryId, WAREHOUSE, "СПБ", 0, 20))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> {
              assertThat(problem.status().value()).isEqualTo(503);
              assertThat(problem.code()).isEqualTo("CABIN_CATALOG_INVALID_RESPONSE");
              assertThat(problem.getMessage()).doesNotContain("asset", "UUID");
            });
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }
}
