package dev.buhanzaz.rwms.logistics.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CabinFurnitureRequirement;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCabinEquipmentSelection;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerBookingResponse;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCabinRentalTerm;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCheckoutRequest;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerSessionState;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerAuthorizer;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.PresentationBookingResponse;
import dev.buhanzaz.rwms.logistics.inquiry.service.ClientPresentationService;
import dev.buhanzaz.rwms.logistics.inquiry.service.PresentationBookingService;
import dev.buhanzaz.rwms.logistics.service.CabinFurnitureTaskService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Verifies retry-safe creation of immediate per-cabin furniture work after customer booking. */
class CustomerCheckoutServiceTest {
  private static final UUID SUBJECT =
      UUID.fromString("00000000-0000-0000-0000-000000000401");
  private static final UUID INQUIRY =
      UUID.fromString("00000000-0000-0000-0000-000000000402");
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000403");
  private static final UUID CABIN =
      UUID.fromString("00000000-0000-0000-0000-000000000404");
  private static final UUID EQUIPMENT =
      UUID.fromString("00000000-0000-0000-0000-000000000405");
  private static final UUID SLOT =
      UUID.fromString("00000000-0000-0000-0000-000000000406");
  private static final UUID BOOKING =
      UUID.fromString("00000000-0000-0000-0000-000000000407");
  private static final UUID ORDER =
      UUID.fromString("00000000-0000-0000-0000-000000000408");
  private static final UUID RECOVERY_LEASE =
      UUID.fromString("00000000-0000-0000-0000-000000000410");

  @Test
  void checkoutUsesDisplayedOneMonthDefaultForAnExistingIncompleteCart() {
    CustomerRentalService rentals = mock(CustomerRentalService.class);
    CustomerRentalSessionStore sessions = mock(CustomerRentalSessionStore.class);
    CustomerCheckoutStore checkoutStore = mock(CustomerCheckoutStore.class);
    CustomerEquipmentCodec equipmentCodec = mock(CustomerEquipmentCodec.class);
    CustomerRentalTermCodec rentalTermCodec = mock(CustomerRentalTermCodec.class);
    CustomerDeliverySlotService slots = mock(CustomerDeliverySlotService.class);
    CustomerBookingService customerBookings = mock(CustomerBookingService.class);
    CustomerAuthorizer access = mock(CustomerAuthorizer.class);
    ClientPresentationService presentations = mock(ClientPresentationService.class);
    PresentationBookingService bookings = mock(PresentationBookingService.class);
    CabinFurnitureTaskService furnitureTasks = mock(CabinFurnitureTaskService.class);
    CustomerCheckoutService service =
        new CustomerCheckoutService(
            rentals,
            sessions,
            checkoutStore,
            equipmentCodec,
            rentalTermCodec,
            slots,
            customerBookings,
            access,
            presentations,
            bookings,
            furnitureTasks);
    CustomerIdentity identity =
        new CustomerIdentity(SUBJECT, "customer");
    CustomerRentalSession current = mock(CustomerRentalSession.class);
    CustomerBookingResponse expected = mock(CustomerBookingResponse.class);
    CustomerCheckoutRequest request = new CustomerCheckoutRequest(9L, SLOT, 3L);
    UUID commandKey = UUID.fromString("00000000-0000-0000-0000-000000000409");
    List<CustomerCabinRentalTerm> defaulted =
        List.of(new CustomerCabinRentalTerm(CABIN, 1L));
    when(current.getState()).thenReturn(CustomerSessionState.ACTIVE);
    when(current.getRentalTermsJson()).thenReturn("[]");
    when(current.getEquipmentSelectionJson()).thenReturn("[]");
    when(rentals.requiredSession(identity, INQUIRY)).thenReturn(current);
    when(rentals.selectedCabinIds(identity, INQUIRY)).thenReturn(List.of(CABIN));
    when(rentalTermCodec.decode("[]")).thenReturn(List.of());
    when(rentalTermCodec.completeWithDefaults("[]", Set.of(CABIN))).thenReturn(defaulted);
    when(equipmentCodec.decode("[]")).thenReturn(List.of());
    when(checkoutStore.prepare(
            eq(SUBJECT),
            eq(INQUIRY),
            eq(9L),
            eq(commandKey),
            anyString(),
            eq(SLOT),
            eq(3L)))
        .thenReturn(
            new CustomerCheckoutStore.Preparation(current, null, true, false, commandKey));
    when(customerBookings.response(identity, current, "COMPLETED", null)).thenReturn(expected);

    assertThat(service.checkout(identity, INQUIRY, commandKey, request)).isSameAs(expected);
    verify(rentalTermCodec).completeWithDefaults("[]", Set.of(CABIN));
  }

  @Test
  void retriesFurnitureCreationWithOneStableTaskKeyBeforeCompletingSession() {
    CustomerRentalService rentals = mock(CustomerRentalService.class);
    CustomerRentalSessionStore sessions = mock(CustomerRentalSessionStore.class);
    CustomerCheckoutStore checkoutStore = mock(CustomerCheckoutStore.class);
    CustomerEquipmentCodec equipmentCodec = mock(CustomerEquipmentCodec.class);
    CustomerRentalTermCodec rentalTermCodec = mock(CustomerRentalTermCodec.class);
    CustomerDeliverySlotService slots = mock(CustomerDeliverySlotService.class);
    CustomerBookingService customerBookings = mock(CustomerBookingService.class);
    CustomerAuthorizer access = mock(CustomerAuthorizer.class);
    ClientPresentationService presentations = mock(ClientPresentationService.class);
    PresentationBookingService bookings = mock(PresentationBookingService.class);
    CabinFurnitureTaskService furnitureTasks = mock(CabinFurnitureTaskService.class);
    CustomerCheckoutService service =
        new CustomerCheckoutService(
            rentals,
            sessions,
            checkoutStore,
            equipmentCodec,
            rentalTermCodec,
            slots,
            customerBookings,
            access,
            presentations,
            bookings,
            furnitureTasks);
    CustomerIdentity identity =
        new CustomerIdentity(SUBJECT, "customer");
    CustomerRentalSession pending = session(CustomerSessionState.CHECKOUT_PENDING);
    CustomerRentalSession completed = session(CustomerSessionState.BOOKED);
    CustomerDeliverySlot confirmedSlot = mock(CustomerDeliverySlot.class);
    LocalDate deliveryDate = LocalDate.of(2026, 9, 10);
    when(sessions.list(SUBJECT)).thenReturn(List.of(pending));
    when(sessions.claimPendingBooking(SUBJECT, INQUIRY))
        .thenReturn(
            Optional.of(
                new CustomerRentalSessionStore.CheckoutRecoveryClaim(
                    SUBJECT, INQUIRY, RECOVERY_LEASE, pending)));
    when(bookings.status("presentation-token", BOOKING))
        .thenReturn(new PresentationBookingResponse(BOOKING, "COMPLETED", ORDER, "/status", null));
    when(slots.confirm(identity, INQUIRY, SLOT, BOOKING, ORDER)).thenReturn(confirmedSlot);
    when(confirmedSlot.getDeliveryDate()).thenReturn(deliveryDate);
    when(equipmentCodec.decode("equipment-json"))
        .thenReturn(List.of(new CustomerCabinEquipmentSelection(CABIN, EQUIPMENT, 2L)));
    when(
            sessions.completeBooking(SUBJECT, INQUIRY, BOOKING, ORDER, RECOVERY_LEASE))
        .thenReturn(completed);
    when(customerBookings.response(identity, completed, "COMPLETED", null))
        .thenReturn(mock(CustomerBookingResponse.class));

    assertThat(service.bookings(identity)).hasSize(1);
    assertThat(service.bookings(identity)).hasSize(1);

    ArgumentCaptor<UUID> taskKeys = ArgumentCaptor.forClass(UUID.class);
    verify(furnitureTasks, times(2))
        .create(
            eq(SUBJECT),
            taskKeys.capture(),
            eq(WAREHOUSE),
            eq(CABIN),
            eq(deliveryDate),
            eq(List.of(new CabinFurnitureRequirement(EQUIPMENT, 2L))));
    assertThat(taskKeys.getAllValues()).containsExactly(taskKeys.getValue(), taskKeys.getValue());
    verify(sessions, times(2))
        .completeBooking(SUBJECT, INQUIRY, BOOKING, ORDER, RECOVERY_LEASE);
  }

  @Test
  void scheduledRecoveryPersistsSafeBackoffReasonAfterUnexpectedFailure() {
    CustomerRentalService rentals = mock(CustomerRentalService.class);
    CustomerRentalSessionStore sessions = mock(CustomerRentalSessionStore.class);
    CustomerCheckoutStore checkoutStore = mock(CustomerCheckoutStore.class);
    CustomerEquipmentCodec equipmentCodec = mock(CustomerEquipmentCodec.class);
    CustomerRentalTermCodec rentalTermCodec = mock(CustomerRentalTermCodec.class);
    CustomerDeliverySlotService slots = mock(CustomerDeliverySlotService.class);
    CustomerBookingService customerBookings = mock(CustomerBookingService.class);
    CustomerAuthorizer access = mock(CustomerAuthorizer.class);
    ClientPresentationService presentations = mock(ClientPresentationService.class);
    PresentationBookingService bookings = mock(PresentationBookingService.class);
    CabinFurnitureTaskService furnitureTasks = mock(CabinFurnitureTaskService.class);
    CustomerCheckoutService service =
        new CustomerCheckoutService(
            rentals,
            sessions,
            checkoutStore,
            equipmentCodec,
            rentalTermCodec,
            slots,
            customerBookings,
            access,
            presentations,
            bookings,
            furnitureTasks);
    CustomerRentalSession pending = session(CustomerSessionState.CHECKOUT_PENDING);
    var claim =
        new CustomerRentalSessionStore.CheckoutRecoveryClaim(
            SUBJECT, INQUIRY, RECOVERY_LEASE, pending);
    when(sessions.claimPendingBookings()).thenReturn(List.of(claim));
    when(bookings.status("presentation-token", BOOKING))
        .thenThrow(new IllegalStateException("raw dependency failure"));
    when(sessions.failCheckoutRecovery(
            SUBJECT,
            INQUIRY,
            RECOVERY_LEASE,
            "CUSTOMER_CHECKOUT_DEPENDENCY_PENDING"))
        .thenReturn(
            new CustomerRentalSessionStore.RecoveryFailure(
                1, OffsetDateTime.parse("2026-08-31T09:00:02Z"), false));
    when(sessions.required(SUBJECT, INQUIRY)).thenReturn(pending);

    service.reconcilePending();

    verify(sessions)
        .failCheckoutRecovery(
            SUBJECT,
            INQUIRY,
            RECOVERY_LEASE,
            "CUSTOMER_CHECKOUT_DEPENDENCY_PENDING");
  }

  private static CustomerRentalSession session(CustomerSessionState state) {
    CustomerRentalSession session = mock(CustomerRentalSession.class);
    when(session.getState()).thenReturn(state);
    when(session.getCustomerSubjectId()).thenReturn(SUBJECT);
    when(session.getInquiryId()).thenReturn(INQUIRY);
    when(session.getWarehouseId()).thenReturn(WAREHOUSE);
    when(session.getDeliverySlotId()).thenReturn(SLOT);
    when(session.getBookingId()).thenReturn(BOOKING);
    when(session.getOrderId()).thenReturn(ORDER);
    when(session.getPresentationToken()).thenReturn("presentation-token");
    when(session.getEquipmentSelectionJson()).thenReturn("equipment-json");
    return session;
  }
}
