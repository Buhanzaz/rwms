package dev.buhanzaz.rwms.logistics.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.buhanzaz.rwms.logistics.customer.api.CustomerBookingChangeApiModels.CreateCustomerBookingChangeQuoteRequest;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerBookingChangeApiModels.CustomerBookingChangeQuoteResponse;
import dev.buhanzaz.rwms.logistics.customer.domain.*;
import dev.buhanzaz.rwms.logistics.customer.repository.*;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseIdentity;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real HTTP/JPA/PostgreSQL proof of quote recovery, per-manager access and atomic settlement. */
@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate", "spring.task.scheduling.enabled=false",
      "rwms.platform.kafka.enabled=false", "rwms.logistics.rental-inquiry.outbox-enabled=false",
      "AUTH_ISSUER=http://auth.test", "PANEL_ORIGIN=http://panel.test"
    })
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CustomerBookingChangeIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  private static final Instant NOW = Instant.parse("2026-09-05T08:00:00Z");
  private static final String ALERTS = "/api/logistics/v1/rental-booking-change-alerts";
  private static final String PENDING = "/api/logistics/v1/rental-booking-change-quotes";
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired CustomerBookingChangeService service;
  @Autowired CustomerBookingChangeChargeStore store;
  @Autowired CustomerRentalSessionRepository sessions;
  @Autowired CustomerDeliverySlotRepository slots;
  @Autowired CustomerBookingMutationRepository mutations;
  @Autowired CustomerBookingChangeChargeRepository charges;
  @Autowired PlatformTransactionManager transactions;
  @MockitoBean LogisticsDependencyGateway dependencies;
  @MockitoBean Clock clock;
  private UUID subject, client, inquiry, order, warehouse, booking, oldSlot;

  @BeforeEach
  void seedOwnedBooking() {
    when(clock.instant()).thenReturn(NOW);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    jdbc.execute("truncate table order_client cascade");
    jdbc.update(
        "update rental_settings set version=7, late_change_notice_days=2,"
            + " late_change_fee_mode='FIXED', late_change_fee_value=1500,"
            + " rental_support_phone='+74951234567'");
    subject = UUID.randomUUID();
    client = UUID.randomUUID();
    inquiry = UUID.randomUUID();
    order = UUID.randomUUID();
    warehouse = UUID.randomUUID();
    booking = UUID.randomUUID();
    jdbc.update(
        """
        insert into order_client(id,version,client_type,display_name,normalized_name,created_by_subject_id,
          creation_idempotency_key,creation_request_sha256,phone,normalized_phone,responsible_manager_id,created_at,updated_at)
        values (?,0,'INDIVIDUAL','Test customer',?,?,?,?,'+79990000001','+79990000001',?,clock_timestamp(),clock_timestamp())
        """,
        client,
        "test-" + client,
        subject,
        UUID.randomUUID(),
        "a".repeat(64),
        subject);
    jdbc.update(
        """
        insert into rental_order(id,version,order_number,status,client_id,manager_id,manager_display_name,
          created_by_subject_id,created_by_display_name,created_by_role,warehouse_id,creation_idempotency_key,
          creation_request_sha256,created_at,updated_at)
        values (?,0,?,'DRAFT',?,?,'Customer',?,'Customer','CUSTOMER',?,?,?,clock_timestamp(),clock_timestamp())
        """,
        order,
        "ORD-%019d".formatted(order.getMostSignificantBits() & Long.MAX_VALUE),
        client,
        subject,
        subject,
        warehouse,
        UUID.randomUUID(),
        "b".repeat(64));
    jdbc.update(
        """
        insert into rental_inquiry(id,version,client_id,manager_id,manager_display_name,manager_role,
          warehouse_id,state,creation_idempotency_key,created_at,updated_at)
        values (?,0,?,?,'Customer','CUSTOMER',?,'ACTIVE',?,clock_timestamp(),clock_timestamp())
        """,
        inquiry,
        client,
        subject,
        warehouse,
        UUID.randomUUID());
    oldSlot = offer(LocalDate.of(2026, 9, 6)).getId();
    jdbc.update(
        "update customer_delivery_slot set state='CONFIRMED',booking_id=?,order_id=? where id=?",
        booking,
        order,
        oldSlot);
    UUID presentation = UUID.randomUUID();
    jdbc.update(
        """
        insert into client_presentation(id,version,inquiry_id,revision,warehouse_id,state,expires_at,view_until,
          booked_order_id,last_publish_idempotency_key,last_publish_request_sha256,created_at,updated_at,booked_at)
        values (?,3,?,1,?,'BOOKED',clock_timestamp()+interval '1 hour',clock_timestamp()+interval '2 hours',
          ?,?,?,clock_timestamp(),clock_timestamp(),clock_timestamp())
        """,
        presentation,
        inquiry,
        warehouse,
        order,
        UUID.randomUUID(),
        "b".repeat(64));
    jdbc.update(
        """
        insert into presentation_booking(id,version,presentation_id,presentation_revision,idempotency_key,order_id,
          selected_item_ids_json,state,attempt_count,created_at,updated_at,completed_at)
        values (?,5,?,1,?,?,'["00000000-0000-0000-0000-000000000001"]'::jsonb,
          'COMPLETED',1,clock_timestamp(),clock_timestamp(),clock_timestamp())
        """,
        booking,
        presentation,
        UUID.randomUUID(),
        order);
    jdbc.update(
        """
        insert into customer_rental_session(id,version,inquiry_id,customer_subject_id,warehouse_id,state,
          delivery_slot_id,booking_id,order_id,presentation_token,created_at,updated_at)
        values (?,4,?,?,?,'BOOKED',?,?,?,'test-receipt',clock_timestamp(),clock_timestamp())
        """,
        UUID.randomUUID(),
        inquiry,
        subject,
        warehouse,
        oldSlot,
        booking,
        order);
    when(dependencies.readWarehouseIdentity(warehouse))
        .thenReturn(new WarehouseIdentity(warehouse, 3, true, "Europe/Moscow"));
  }

  @Test
  void receiptDeliveryQuoteFollowsExactSelectedSlotBeforeSessionOrderAttachment() {
    jdbc.update(
        """
        update customer_rental_session set state='ACTIVE',order_id=null,booking_id=null,presentation_token=null
        where inquiry_id=?
        """,
        inquiry);
    var quote = slots.findCheckoutQuoteByOrderId(order).orElseThrow();
    assertThat(quote.getId()).isEqualTo(oldSlot);
    assertThat(quote.getDeliveryPriceRubles()).isEqualTo(10_000L);
    assertThat(slots.findCheckoutQuoteByOrderId(UUID.randomUUID())).isEmpty();
    var anotherOffer = offer(quote.getDeliveryDate().plusDays(1));
    assertThat(slots.findCheckoutQuoteByOrderId(order).orElseThrow().getId()).isEqualTo(oldSlot);
    jdbc.update(
        "update customer_rental_session set delivery_slot_id=? where inquiry_id=?",
        anotherOffer.getId(),
        inquiry);
    assertThat(slots.findCheckoutQuoteByOrderId(order).orElseThrow().getId())
        .isEqualTo(anotherOffer.getId());
  }

  @Test
  void exactCustomerQuoteIsStringMoneyReplayableAndDoesNotAcceptMissingFields() throws Exception {
    UUID key = UUID.randomUUID();
    String path = customerPath() + "/change-quotes";
    String body =
        "{\"expectedVersion\":4,\"operation\":\"CANCEL\",\"slotId\":null,\"slotVersion\":null}";
    mvc.perform(
            post(path)
                .with(customer(subject))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amountRubles").value("1500"))
        .andExpect(jsonPath("$.applicationState").value("OFFERED"))
        .andExpect(jsonPath("$.targetDeliveryDate").isEmpty());
    var charge = charges.findByCustomerSubjectIdAndIdempotencyKey(subject, key).orElseThrow();
    mvc.perform(get(path + "/" + charge.getId()).with(customer(UUID.randomUUID())))
        .andExpect(status().isNotFound());
    mvc.perform(
            post(path)
                .with(customer(subject))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.quoteId").value(charge.getId().toString()));
    mvc.perform(
            post(path)
                .with(customer(subject))
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":4,\"operation\":\"CANCEL\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post(customerPath() + "/cancel")
                .with(customer(subject))
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":4}"))
        .andExpect(status().isBadRequest());
    assertThat(charges.count()).isOne();
  }

  @Test
  void unknownPolicyAndCanonicalTimezoneFailureAreExplicit() throws Exception {
    jdbc.update("update rental_settings set late_change_fee_mode=null,late_change_fee_value=null");
    var quote = quote();
    mvc.perform(get(customerPath() + "/change-quotes/" + quote.quoteId()).with(customer(subject)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.settlement").value("POLICY_UNCONFIGURED"))
        .andExpect(jsonPath("$.amountRubles").isEmpty());
    when(dependencies.readWarehouseIdentity(warehouse))
        .thenReturn(new WarehouseIdentity(warehouse, 3, true, "Not/AZone"));
    assertThatThrownBy(this::quote)
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            error -> assertThat(error.code()).isEqualTo("CUSTOMER_CHANGE_TIME_ZONE_UNAVAILABLE"));
  }

  @Test
  void bindingAndSettlementRollBackTogetherAndCompletionIsNotJustAClick() {
    var quote = quote();
    var tx = new TransactionTemplate(transactions);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      bind(quote);
                      throw new IllegalStateException("Owner failed before commit");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(charges.findById(quote.quoteId()).orElseThrow().getApplicationState())
        .isEqualTo(CustomerChangeApplicationState.OFFERED);
    assertThat(mutations.count()).isZero();
    UUID mutation = tx.execute(status -> bind(quote));
    assertThat(service.get(identity(), booking, quote.quoteId()).settlement())
        .isEqualTo(CustomerChangeSettlement.PAYMENT_REQUIRED);
    tx.executeWithoutResult(
        status -> {
          var change = mutations.findForUpdate(mutation).orElseThrow();
          UUID lease = UUID.randomUUID();
          assertThat(change.claim(lease, now(), now().plusMinutes(1))).isTrue();
          change.complete(lease, now());
          mutations.saveAndFlush(change);
          store.complete(mutation);
        });
    var result = service.get(identity(), booking, quote.quoteId());
    assertThat(result.applicationState()).isEqualTo(CustomerChangeApplicationState.APPLIED);
    assertThat(result.settlement()).isEqualTo(CustomerChangeSettlement.TEST_PAID);
    assertThat(result.version()).isGreaterThan(quote.version());
    assertThat(
            jdbc.queryForObject(
                "select new_values->>'amountRubles' from rental_order_audit_event where"
                    + " subject_type='CUSTOMER_BOOKING_CHANGE_CHARGE'",
                String.class))
        .isEqualTo("1500");
  }

  @Test
  void everyWarehouseManagerHasIndependentReadReceiptWithoutOrderAccess() throws Exception {
    var replacement = offer(LocalDate.of(2026, 9, 8));
    var change =
        mutations.saveAndFlush(
            CustomerBookingMutation.completedReschedule(
                subject,
                booking,
                inquiry,
                order,
                UUID.randomUUID(),
                "c".repeat(64),
                4,
                0,
                oldSlot,
                replacement.getId(),
                "CUSTOMER_AGREED",
                subject,
                null,
                "{}",
                now()));
    UUID first = UUID.randomUUID(), second = UUID.randomUUID(), key = UUID.randomUUID();
    for (UUID manager : List.of(first, second)) {
      mvc.perform(get(ALERTS).with(manager(manager, warehouse, "VIEW")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(1))
          .andExpect(jsonPath("$[0].canOpenOrder").value(false));
    }
    String ack = ALERTS + "/" + change.getId() + "/acknowledgement";
    for (int retry = 0; retry < 2; retry++) {
      mvc.perform(
              post(ack)
                  .with(manager(first, warehouse, "VIEW"))
                  .header("Idempotency-Key", key)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"expectedVersion\":0}"))
          .andExpect(status().isNoContent());
    }
    mvc.perform(get(ALERTS).with(manager(first, warehouse, "VIEW")))
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(get(ALERTS).with(manager(second, warehouse, "VIEW")))
        .andExpect(jsonPath("$.length()").value(1));
    mvc.perform(get(ALERTS).with(manager(second, UUID.randomUUID(), "VIEW")))
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(
            post(ack)
                .with(manager(second, UUID.randomUUID(), "VIEW"))
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0}"))
        .andExpect(status().isNotFound());
    mvc.perform(get(ALERTS).with(customer(subject))).andExpect(status().isForbidden());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from customer_booking_change_alert_ack",
                Integer.class))
        .isOne();
  }

  @Test
  void warehouseEditAllowsReasonedWaiverButNeverOpensTheCustomerOrder() throws Exception {
    var quote = quote();
    UUID manager = UUID.randomUUID(), key = UUID.randomUUID();
    String path =
        "/api/logistics/v1/orders/"
            + order
            + "/booking-change-quotes/"
            + quote.quoteId()
            + "/waiver";
    mvc.perform(get(PENDING).with(manager(manager, warehouse, "VIEW")))
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(get(PENDING).with(manager(manager, warehouse, "EDIT")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].quote.quoteId").value(quote.quoteId().toString()));
    String body = "{\"expectedVersion\":0,\"reason\":\"  Форс-мажор  \"}";
    mvc.perform(
            post(path)
                .with(manager(manager, warehouse, "VIEW"))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(path)
                .with(manager(manager, warehouse, "EDIT"))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"reason\":\" \"}"))
        .andExpect(status().isBadRequest());
    for (int retry = 0; retry < 2; retry++) {
      mvc.perform(
              post(path)
                  .with(manager(manager, warehouse, "EDIT"))
                  .header("Idempotency-Key", key)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.settlement").value("WAIVED"))
          .andExpect(jsonPath("$.amountRubles").value("0"));
    }
    mvc.perform(get(PENDING).with(manager(manager, warehouse, "EDIT")))
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(get("/api/logistics/v1/orders/" + order).with(manager(manager, warehouse, "EDIT")))
        .andExpect(status().isNotFound());
    assertThat(charges.findById(quote.quoteId()).orElseThrow().getAmountRubles()).isEqualTo(1500);
    assertThat(charges.findById(quote.quoteId()).orElseThrow().getWaiverReason())
        .isEqualTo("Форс-мажор");
  }

  @Test
  void rescheduleQuoteRetainsAuthoritativeTargetWindowAndRejectsMissingVersionAtDatabase() {
    var replacement = offer(LocalDate.of(2026, 9, 8));
    var quote =
        service.create(
            identity(),
            booking,
            UUID.randomUUID(),
            new CreateCustomerBookingChangeQuoteRequest(
                4L,
                CustomerBookingMutationOperation.RESCHEDULE,
                replacement.getId(),
                replacement.getVersion()));
    assertThat(quote.targetDeliveryDate()).isEqualTo(replacement.getDeliveryDate());
    assertThat(quote.targetWindowStart()).isEqualTo(LocalTime.of(9, 0));
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update customer_booking_change_charge set slot_version=null where id=?",
                    quote.quoteId()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  private UUID bind(CustomerBookingChangeQuoteResponse quote) {
    var session = sessions.findByBookingIdForUpdate(booking).orElseThrow();
    var original = slots.findById(oldSlot).orElseThrow();
    var charge =
        store.admit(
            session,
            original,
            CustomerBookingMutationOperation.CANCEL,
            null,
            null,
            quote.quoteId(),
            quote.version(),
            true);
    var mutation =
        mutations.saveAndFlush(
            CustomerBookingMutation.cancel(
                subject,
                booking,
                inquiry,
                order,
                UUID.randomUUID(),
                "d".repeat(64),
                4,
                0,
                oldSlot,
                now()));
    store.bind(charge, mutation.getId(), true);
    return mutation.getId();
  }

  private CustomerDeliverySlot offer(LocalDate date) {
    return slots.saveAndFlush(
        CustomerDeliverySlot.offer(
            subject,
            inquiry,
            warehouse,
            date,
            CustomerDeliverySlotKind.FIXED_WINDOW,
            LocalTime.of(9, 0),
            LocalTime.of(12, 0),
            "Test address",
            new BigDecimal("59.9"),
            new BigDecimal("30.3"),
            1,
            1800,
            1,
            1,
            1,
            10_000L,
            60,
            true,
            true,
            4,
            2.5,
            8,
            10,
            5,
            2,
            now().plusHours(1)));
  }

  private CustomerBookingChangeQuoteResponse quote() {
    return service.create(
        identity(),
        booking,
        UUID.randomUUID(),
        new CreateCustomerBookingChangeQuoteRequest(
            4L, CustomerBookingMutationOperation.CANCEL, null, null));
  }

  private CustomerIdentity identity() {
    return new CustomerIdentity(subject, "customer");
  }

  private OffsetDateTime now() {
    return NOW.atOffset(ZoneOffset.UTC);
  }

  private String customerPath() {
    return "/api/logistics/customer/v1/bookings/" + booking;
  }

  private JwtRequestPostProcessor customer(UUID id) {
    return jwt()
        .jwt(
            token ->
                token
                    .subject(id.toString())
                    .claim("principal_type", "USER")
                    .claim("global_role", "CUSTOMER")
                    .claim("client_id", "rwms-customer-android")
                    .claim("scope", "customer.rental"))
        .authorities(new SimpleGrantedAuthority("SCOPE_customer.rental"));
  }

  private JwtRequestPostProcessor manager(UUID id, UUID warehouseId, String level) {
    return jwt()
        .jwt(
            token ->
                token
                    .subject(id.toString())
                    .claim("principal_type", "USER")
                    .claim("global_role", "RENTAL_MANAGER")
                    .claim("client_id", "rwms-rental-manager-web")
                    .claim("scope", "rental.manage")
                    .claim("rentalAccess", true)
                    .claim(
                        "warehouse_access",
                        List.of(Map.of("warehouseId", warehouseId.toString(), "level", level))))
        .authorities(new SimpleGrantedAuthority("SCOPE_rental.manage"));
  }
}
