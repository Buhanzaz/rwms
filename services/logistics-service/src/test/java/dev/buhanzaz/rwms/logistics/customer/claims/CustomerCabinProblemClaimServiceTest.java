package dev.buhanzaz.rwms.logistics.customer.claims;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCabinProblemCategory;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCabinProblemPhase;
import dev.buhanzaz.rwms.logistics.customer.claims.domain.CustomerCabinProblemAction;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerCabinProblem;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerCabinProblemRepository;
import dev.buhanzaz.rwms.logistics.order.domain.ClientType;
import dev.buhanzaz.rwms.logistics.order.domain.OrderClient;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** Covers warehouse authorization delegation and the explicit claim version fence. */
class CustomerCabinProblemClaimServiceTest {
  private static final UUID ACTOR =
      UUID.fromString("00000000-0000-0000-0000-000000000103");
  private static final UUID PROBLEM =
      UUID.fromString("00000000-0000-0000-0000-000000000104");
  private static final UUID ORDER =
      UUID.fromString("00000000-0000-0000-0000-000000000105");
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000106");

  @Test
  void startsProgressThroughWarehouseFenceAndRecordsAnImmutableAction() {
    Fixture fixture = fixture();

    CustomerCabinProblemClaimView view = fixture.service().startProgress(fixture.actor(), PROBLEM, 0);

    assertThat(view.status()).isEqualTo(CustomerCabinProblemStatus.IN_PROGRESS);
    assertThat(view.orderNumber()).isEqualTo("ORD-000012");
    assertThat(view.clientDisplayName()).isEqualTo("ООО «СтройМонтаж»");
    assertThat(view.clientType()).isEqualTo(ClientType.LEGAL_ENTITY);
    assertThat(view.clientPhone()).isEqualTo("+79990000000");
    assertThat(view.orderContactPhone()).isEqualTo("+79990000001");
    assertThat(view.deliveryAddress()).isEqualTo("Санкт-Петербург, Тестовая улица, 1");
    verify(fixture.problems()).findById(PROBLEM);
    verify(fixture.orders()).findWithClientById(ORDER);
    verify(fixture.access()).isVisible(fixture.actor(), fixture.order());
    verify(fixture.access()).requireWarehouseEdit(fixture.actor(), WAREHOUSE);
    ArgumentCaptor<CustomerCabinProblemAction> action =
        ArgumentCaptor.forClass(CustomerCabinProblemAction.class);
    verify(fixture.actions()).save(action.capture());
    assertThat(action.getValue().getActionKind())
        .isEqualTo(CustomerCabinProblemActionKind.STATUS_TRANSITION);
    assertThat(action.getValue().getProblemId()).isEqualTo(PROBLEM);
    assertThat(action.getValue().getActorSubjectId()).isEqualTo(ACTOR);
  }

  @Test
  void resolvesOnlyThroughTheExplicitVersionFence() {
    Fixture fixture = fixture();
    fixture.problem().startProgress(0);
    ReflectionTestUtils.setField(fixture.problem(), "version", 1L);

    CustomerCabinProblemClaimView view =
        fixture
            .service()
            .resolve(
                fixture.actor(),
                PROBLEM,
                1,
                CustomerCabinProblemResolutionKind.DISCOUNT,
                "Согласована скидка");

    assertThat(view.status()).isEqualTo(CustomerCabinProblemStatus.RESOLVED);
    assertThat(view.resolutionKind()).isEqualTo(CustomerCabinProblemResolutionKind.DISCOUNT);
    assertThat(view.resolvedBySubjectId()).isEqualTo(ACTOR);
    ArgumentCaptor<CustomerCabinProblemAction> action =
        ArgumentCaptor.forClass(CustomerCabinProblemAction.class);
    verify(fixture.actions()).save(action.capture());
    assertThat(action.getValue().getActionKind())
        .isEqualTo(CustomerCabinProblemActionKind.RESOLUTION_DECISION);
    assertThat(action.getValue().getLifecycleStatus()).isEqualTo(CustomerCabinProblemStatus.RESOLVED);
  }

  @Test
  void rejectsStaleExpectedVersionWithoutWritingAClaimOrAction() {
    Fixture fixture = fixture();

    assertThatThrownBy(() -> fixture.service().startProgress(fixture.actor(), PROBLEM, 7))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> {
              assertThat(problem.code()).isEqualTo("CUSTOMER_CABIN_PROBLEM_VERSION_CONFLICT");
              assertThat(problem.status().value()).isEqualTo(409);
            });

    verify(fixture.problems(), never()).saveAndFlush(any());
    verify(fixture.actions(), never()).save(any());
  }

  @Test
  void letsRentalManagerResolveTheSharedQueueWithoutWarehouseGrants() {
    Fixture fixture = fixture();
    OrderActor manager = rentalManager();

    CustomerCabinProblemClaimView view = fixture.service().startProgress(manager, PROBLEM, 0);

    assertThat(view.status()).isEqualTo(CustomerCabinProblemStatus.IN_PROGRESS);
    verify(fixture.access(), never()).requireWarehouseEdit(manager, WAREHOUSE);
    verify(fixture.access(), never()).isVisible(manager, fixture.order());
  }

  @Test
  void returnsClaimActionHistoryInChronologicalOrder() {
    Fixture fixture = fixture();
    CustomerCabinProblemAction transition =
        CustomerCabinProblemAction.statusTransition(
            PROBLEM,
            CustomerCabinProblemStatus.OPEN,
            ACTOR,
            OffsetDateTime.parse("2026-09-02T09:00:00Z"));
    CustomerCabinProblemAction resolution =
        CustomerCabinProblemAction.resolutionDecision(
            PROBLEM,
            CustomerCabinProblemStatus.IN_PROGRESS,
            CustomerCabinProblemResolutionKind.DISCOUNT,
            ACTOR,
            "Согласована скидка",
            OffsetDateTime.parse("2026-09-02T10:00:00Z"));
    when(fixture.actions().findAllByProblemIdOrderByOccurredAtDescIdDesc(PROBLEM))
        .thenReturn(List.of(resolution, transition));

    CustomerCabinProblemClaimView view = fixture.service().get(fixture.actor(), PROBLEM);

    assertThat(view.actions())
        .extracting(CustomerCabinProblemActionView::actionKind)
        .containsExactly(
            CustomerCabinProblemActionKind.STATUS_TRANSITION,
            CustomerCabinProblemActionKind.RESOLUTION_DECISION);
  }

  private static Fixture fixture() {
    CustomerCabinProblemRepository problems = mock(CustomerCabinProblemRepository.class);
    CustomerCabinProblemActionRepository actions = mock(CustomerCabinProblemActionRepository.class);
    RentalOrderRepository orders = mock(RentalOrderRepository.class);
    OrderAuthorizer access = mock(OrderAuthorizer.class);
    CustomerCabinProblem problem = problem();
    RentalOrder order = mock(RentalOrder.class);
    OrderClient client = mock(OrderClient.class);
    OrderActor actor = actor();
    when(problems.findById(PROBLEM)).thenReturn(Optional.of(problem));
    when(orders.findWithClientById(ORDER)).thenReturn(Optional.of(order));
    when(order.getClient()).thenReturn(client);
    when(order.getOrderNumber()).thenReturn("ORD-000012");
    when(client.getDisplayName()).thenReturn("ООО «СтройМонтаж»");
    when(client.getClientType()).thenReturn(ClientType.LEGAL_ENTITY);
    when(client.getPhone()).thenReturn("+79990000000");
    when(order.getContactPhone()).thenReturn("+79990000001");
    when(order.getDeliveryAddress()).thenReturn("Санкт-Петербург, Тестовая улица, 1");
    when(order.getWarehouseId()).thenReturn(WAREHOUSE);
    when(access.isVisible(actor, order)).thenReturn(true);
    when(problems.saveAndFlush(any(CustomerCabinProblem.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(actions.findAllByProblemIdOrderByOccurredAtDescIdDesc(PROBLEM))
        .thenReturn(List.of());
    return new Fixture(
        new CustomerCabinProblemClaimService(
            problems,
            actions,
            orders,
            access,
            Clock.fixed(Instant.parse("2026-09-02T10:00:00Z"), ZoneOffset.UTC)),
        problems,
        actions,
        orders,
        access,
        problem,
        order,
        actor);
  }

  private static CustomerCabinProblem problem() {
    CustomerCabinProblem problem =
        CustomerCabinProblem.create(
            UUID.fromString("00000000-0000-0000-0000-000000000107"),
            UUID.fromString("00000000-0000-0000-0000-000000000108"),
            ORDER,
            WAREHOUSE,
            UUID.fromString("00000000-0000-0000-0000-000000000109"),
            UUID.fromString("00000000-0000-0000-0000-000000000110"),
            UUID.fromString("00000000-0000-0000-0000-000000000111"),
            UUID.fromString("00000000-0000-0000-0000-000000000112"),
            CustomerCabinProblemCategory.OTHER,
            CustomerCabinProblemPhase.BEFORE_ACCEPTANCE,
            "Нужна проверка дефекта",
            "[]",
            UUID.fromString("00000000-0000-0000-0000-000000000113"),
            "a".repeat(64),
            OffsetDateTime.parse("2026-09-02T08:00:00Z"));
    ReflectionTestUtils.setField(problem, "id", PROBLEM);
    return problem;
  }

  private static OrderActor actor() {
    return new OrderActor(
        ACTOR,
        "WAREHOUSE_MANAGER",
        "manager",
        Set.of(WAREHOUSE),
        Set.of(WAREHOUSE),
        false,
        true,
        true,
        true);
  }

  private static OrderActor rentalManager() {
    return new OrderActor(
        ACTOR,
        "RENTAL_MANAGER",
        "manager",
        Set.of(),
        Set.of(),
        false,
        false,
        true,
        true);
  }

  private record Fixture(
      CustomerCabinProblemClaimService service,
      CustomerCabinProblemRepository problems,
      CustomerCabinProblemActionRepository actions,
      RentalOrderRepository orders,
      OrderAuthorizer access,
      CustomerCabinProblem problem,
      RentalOrder order,
      OrderActor actor) {}
}
