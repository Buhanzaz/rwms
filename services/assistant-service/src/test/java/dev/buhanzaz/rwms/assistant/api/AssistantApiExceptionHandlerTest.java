package dev.buhanzaz.rwms.assistant.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.assistant.service.AssistantInquiryArchivedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

/** Verifies that a normal terminal rental-inquiry response is never reported as an outage. */
class AssistantApiExceptionHandlerTest {
  @Test
  void mapsArchivedInquiryToConflictInsteadOfBadGateway() {
    ProblemDetail problem =
        new AssistantApiExceptionHandler().inquiryArchived(new AssistantInquiryArchivedException());

    assertThat(problem.getStatus()).isEqualTo(409);
    assertThat(problem.getProperties()).containsEntry("code", "ASSISTANT_INQUIRY_ARCHIVED");
  }
}
