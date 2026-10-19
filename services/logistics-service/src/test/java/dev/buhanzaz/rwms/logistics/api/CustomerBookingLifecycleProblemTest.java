package dev.buhanzaz.rwms.logistics.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import dev.buhanzaz.rwms.platform.web.RwmsProblemDetailFactory;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Verifies that CustomerApp lifecycle conflicts retain their actionable Russian domain detail. */
class CustomerBookingLifecycleProblemTest {
  @Test
  void customerBookingConflictIsRenderedAsRussianProblemDetails() {
    LogisticsProblemHandler handler = new LogisticsProblemHandler(new RwmsProblemDetailFactory());
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI())
        .thenReturn("/api/logistics/customer/v1/bookings/booking/reschedule");
    when(request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE))
        .thenReturn(UUID.randomUUID().toString());

    var response =
        handler.orderProblem(
            new OrderProblemException(
                HttpStatus.CONFLICT,
                "CUSTOMER_BOOKING_NOT_EDITABLE",
                "Бронирование нельзя перенести после начала отгрузки"),
            request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("CUSTOMER_BOOKING_NOT_EDITABLE");
    assertThat(response.getBody().detail())
        .isEqualTo("Бронирование нельзя перенести после начала отгрузки");
  }
}
