package dev.buhanzaz.rwms.logistics.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerBookingResponse;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerSessionState;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerAuthorizer;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.inquiry.service.ClientPresentationService;
import dev.buhanzaz.rwms.logistics.inquiry.service.PresentationBookingService;
import dev.buhanzaz.rwms.logistics.service.CabinFurnitureTaskService;
import dev.buhanzaz.rwms.logistics.order.service.RentalOrderPaymentService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pins cancelled and rescheduled sessions in the existing CustomerApp booking-list projection. */
class CustomerBookingLifecycleProjectionTest {
  @Test
  void expiryWinningAgainstCheckoutRecoveryDoesNotResurrectPendingStatus() {
    UUID subject = UUID.randomUUID();
    UUID inquiry = UUID.randomUUID();
    UUID booking = UUID.randomUUID();
    UUID lease = UUID.randomUUID();
    var identity = new CustomerIdentity(subject, "Customer");
    var pending = mock(CustomerRentalSession.class);
    var cancelled = mock(CustomerRentalSession.class);
    when(pending.getBookingId()).thenReturn(booking);
    when(pending.getInquiryId()).thenReturn(inquiry);
    when(pending.getState()).thenReturn(CustomerSessionState.CHECKOUT_PENDING);
    when(pending.getPresentationToken()).thenReturn("token");
    when(cancelled.getState()).thenReturn(CustomerSessionState.CANCELLED);
    var sessions = mock(CustomerRentalSessionStore.class);
    when(sessions.list(subject)).thenReturn(List.of(pending));
    when(sessions.claimPendingBooking(subject, inquiry))
        .thenReturn(
            Optional.of(
                new CustomerRentalSessionStore.CheckoutRecoveryClaim(
                    subject, inquiry, lease, pending)));
    when(sessions.required(subject, inquiry)).thenReturn(cancelled);
    when(sessions.failCheckoutRecovery(
            org.mockito.ArgumentMatchers.eq(subject),
            org.mockito.ArgumentMatchers.eq(inquiry),
            org.mockito.ArgumentMatchers.eq(lease),
            org.mockito.ArgumentMatchers.anyString()))
        .thenThrow(new IllegalStateException("Lease ended"));
    var presentationBookings = mock(PresentationBookingService.class);
    when(presentationBookings.status("token", booking))
        .thenThrow(new IllegalStateException("Concurrent expiry"));
    var bookings = mock(CustomerBookingService.class);
    var response = mock(CustomerBookingResponse.class);
    when(bookings.response(identity, cancelled, "CANCELLED", null)).thenReturn(response);
    var service =
        new CustomerCheckoutService(
            mock(CustomerRentalService.class),
            sessions,
            mock(CustomerCheckoutStore.class),
            mock(CustomerEquipmentCodec.class),
            mock(CustomerRentalTermCodec.class),
            mock(CustomerDeliverySlotService.class),
            bookings,
            mock(CustomerAuthorizer.class),
            mock(ClientPresentationService.class),
            presentationBookings,
            mock(CabinFurnitureTaskService.class),
            mock(RentalOrderPaymentService.class));
    assertThat(service.bookings(identity)).containsExactly(response);
  }

  @Test
  void bookingListKeepsCancelledAndRescheduledOutcomesAfterReload() {
    UUID subjectId = UUID.randomUUID();
    CustomerIdentity identity = new CustomerIdentity(subjectId, "customer");
    CustomerRentalSession cancelled = mock(CustomerRentalSession.class);
    CustomerRentalSession rescheduled = mock(CustomerRentalSession.class);
    when(cancelled.getBookingId()).thenReturn(UUID.randomUUID());
    when(cancelled.getState()).thenReturn(CustomerSessionState.CANCELLED);
    when(rescheduled.getBookingId()).thenReturn(UUID.randomUUID());
    when(rescheduled.getState()).thenReturn(CustomerSessionState.BOOKED);
    CustomerRentalSessionStore sessions = mock(CustomerRentalSessionStore.class);
    CustomerBookingService bookings = mock(CustomerBookingService.class);
    CustomerBookingResponse cancelledResponse = mock(CustomerBookingResponse.class);
    CustomerBookingResponse rescheduledResponse = mock(CustomerBookingResponse.class);
    when(sessions.list(subjectId)).thenReturn(List.of(cancelled, rescheduled));
    when(bookings.response(identity, cancelled, "CANCELLED", null)).thenReturn(cancelledResponse);
    when(bookings.response(identity, rescheduled, "COMPLETED", null))
        .thenReturn(rescheduledResponse);
    CustomerCheckoutService service =
        new CustomerCheckoutService(
            mock(CustomerRentalService.class),
            sessions,
            mock(CustomerCheckoutStore.class),
            mock(CustomerEquipmentCodec.class),
            mock(CustomerRentalTermCodec.class),
            mock(CustomerDeliverySlotService.class),
            bookings,
            mock(CustomerAuthorizer.class),
            mock(ClientPresentationService.class),
            mock(PresentationBookingService.class),
            mock(CabinFurnitureTaskService.class),
            mock(RentalOrderPaymentService.class));

    assertThat(service.bookings(identity)).containsExactly(cancelledResponse, rescheduledResponse);
    verify(bookings).response(eq(identity), eq(cancelled), eq("CANCELLED"), eq(null));
    verify(bookings).response(eq(identity), eq(rescheduled), eq("COMPLETED"), eq(null));
  }
}
