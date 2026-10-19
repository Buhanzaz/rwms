package dev.buhanzaz.rwms.assistant.api;

import dev.buhanzaz.rwms.assistant.service.AssistantConflictException;
import dev.buhanzaz.rwms.assistant.service.AssistantInquiryArchivedException;
import dev.buhanzaz.rwms.assistant.service.AssistantNotFoundException;
import dev.buhanzaz.rwms.assistant.service.AssistantUpstreamException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps assistant domain and dependency failures to safe public Problem Details responses. */
@RestControllerAdvice
public class AssistantApiExceptionHandler {
  @ExceptionHandler(AssistantNotFoundException.class)
  ProblemDetail notFound(AssistantNotFoundException failure) {
    return problem(
        HttpStatus.NOT_FOUND, "ASSISTANT_CONVERSATION_NOT_FOUND", "Conversation was not found");
  }

  @ExceptionHandler(AssistantConflictException.class)
  ProblemDetail conflict(AssistantConflictException failure) {
    return problem(
        HttpStatus.CONFLICT,
        "ASSISTANT_CONVERSATION_CONFLICT",
        "Conversation state conflicts with the request");
  }

  @ExceptionHandler(AssistantInquiryArchivedException.class)
  ProblemDetail inquiryArchived(AssistantInquiryArchivedException failure) {
    return problem(
        HttpStatus.CONFLICT,
        "ASSISTANT_INQUIRY_ARCHIVED",
        "The rental inquiry is already completed");
  }

  @ExceptionHandler(AssistantUpstreamException.class)
  ProblemDetail upstream(AssistantUpstreamException failure) {
    return problem(
        HttpStatus.BAD_GATEWAY,
        "ASSISTANT_LOGISTICS_UNAVAILABLE",
        "The rental inquiry service is currently unavailable");
  }

  @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
  ProblemDetail invalidRequest(RuntimeException failure) {
    return problem(
        HttpStatus.BAD_REQUEST, "ASSISTANT_REQUEST_INVALID", "The assistant request is invalid");
  }

  @ExceptionHandler(AccessDeniedException.class)
  ProblemDetail forbidden(AccessDeniedException failure) {
    return problem(HttpStatus.FORBIDDEN, "ASSISTANT_FORBIDDEN", "Assistant access is forbidden");
  }

  private static ProblemDetail problem(HttpStatus status, String code, String detail) {
    ProblemDetail value = ProblemDetail.forStatusAndDetail(status, detail);
    value.setType(URI.create("urn:rwms:problem:" + code.toLowerCase(java.util.Locale.ROOT)));
    value.setProperty("code", code);
    return value;
  }
}
