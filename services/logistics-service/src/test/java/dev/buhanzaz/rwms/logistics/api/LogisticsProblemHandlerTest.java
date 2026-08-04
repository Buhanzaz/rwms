package dev.buhanzaz.rwms.logistics.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.platform.contracts.ApiProblem;
import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import dev.buhanzaz.rwms.platform.web.RwmsProblemDetailFactory;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class LogisticsProblemHandlerTest {

  private static final UUID CORRELATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000701");

  private final LogisticsProblemHandler handler =
      new LogisticsProblemHandler(new RwmsProblemDetailFactory());

  @Test
  void mapsPermanentDependencyRejectionToContractedConflict() {
    ResponseEntity<ApiProblem> response =
        handler.dependency(
            new LogisticsDependencyException(
                LogisticsDependencyException.FailureKind.PERMANENT_REJECTION,
                "raw dependency detail must not be exposed"),
            request());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("LOGISTICS_DEPENDENCY_CONFLICT");
    assertThat(response.getBody().detail())
        .isEqualTo("A dependent service rejected the logistics operation");
    assertThat(response.getBody().correlation().correlationId()).isEqualTo(CORRELATION_ID);
  }

  @Test
  void mapsTransientAndConfigurationFailuresToContractedServiceUnavailable() {
    for (LogisticsDependencyException.FailureKind kind :
        new LogisticsDependencyException.FailureKind[] {
          LogisticsDependencyException.FailureKind.TRANSIENT,
          LogisticsDependencyException.FailureKind.CONFIGURATION
        }) {
      ResponseEntity<ApiProblem> response =
          handler.dependency(new LogisticsDependencyException(kind, "private detail"), request());

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().code()).isEqualTo("LOGISTICS_DEPENDENCY_UNAVAILABLE");
      assertThat(response.getBody().detail())
          .isEqualTo("A service required by logistics is temporarily unavailable");
    }
  }

  private static HttpServletRequest request() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn("/api/logistics/v1/driver-board");
    when(request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE))
        .thenReturn(CORRELATION_ID.toString());
    return request;
  }
}
