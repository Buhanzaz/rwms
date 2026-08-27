package dev.buhanzaz.rwms.logistics.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CabinFurnitureRequirement;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCabinEquipmentSelection;
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
import java.util.List;
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

  @Test
  void retriesFurnitureCreationWithOneStableTaskKeyBeforeCompletingSession() {
    CustomerRentalService rentals = mock(CustomerRentalService.class);
    CustomerRentalSessionStore sessions = mock(CustomerRentalSessionStore.class);
    CustomerCheckoutStore checkoutStore = mock(CustomerCheckoutStore.class);
    CustomerEquipmentCodec equipmentCodec = mock(CustomerEquipmentCodec.class);
    CustomerDeliverySlotService slots = mock(CustomerDeliverySlotService.class);
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
            slots,
            access,
            presentations,
            bookings,
            furnitureTasks);
    CustomerIdentity identity = new CustomerIdentity(SUBJECT, "customer");
    CustomerRentalSession pending = session(CustomerSessionState.CHECKOUT_PENDING);
    CustomerRentalSession completed = session(CustomerSessionState.BOOKED);
    CustomerDeliverySlot confirmedSlot = mock(CustomerDeliverySlot.class);
    LocalDate deliveryDate = LocalDate.of(2026, 9, 10);
    when(sessions.list(SUBJECT)).thenReturn(List.of(pending));
    when(bookings.status("presentation-token", BOOKING))
        .thenReturn(new PresentationBookingResponse(BOOKING, "COMPLETED", ORDER, "/status", null));
    when(slots.confirm(identity, INQUIRY, SLOT, BOOKING, ORDER)).thenReturn(confirmedSlot);
    when(confirmedSlot.getDeliveryDate()).thenReturn(deliveryDate);
    when(equipmentCodec.decode("equipment-json"))
        .thenReturn(List.of(new CustomerCabinEquipmentSelection(CABIN, EQUIPMENT, 2L)));
    when(sessions.completeBooking(SUBJECT, INQUIRY, BOOKING, ORDER)).thenReturn(completed);

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
    verify(sessions, times(2)).completeBooking(SUBJECT, INQUIRY, BOOKING, ORDER);
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
