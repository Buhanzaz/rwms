package dev.buhanzaz.rwms.logistics.inquiry.api;

import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryOutboxRecoveryApiModels.RentalInquiryOutboxRecoveryRequest;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryOutboxRecoveryApiModels.RentalInquiryOutboxRecoveryResponse;
import dev.buhanzaz.rwms.logistics.inquiry.eventing.RentalInquiryBookedOutboxStore;
import dev.buhanzaz.rwms.logistics.security.LogisticsAuthorizer;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Global-administrator boundary for reviewed rental-inquiry outbox recovery. */
@RestController
@Validated
@RequestMapping("/api/logistics/v1/admin/rental-inquiry-outbox")
@RequiredArgsConstructor
public class RentalInquiryOutboxRecoveryController {
  private final RentalInquiryBookedOutboxStore outbox;
  private final LogisticsAuthorizer access;

  /** Requeues intact evidence once per version-fenced review and returns its stable receipt. */
  @PostMapping("/{eventId}/recovery")
  public ResponseEntity<RentalInquiryOutboxRecoveryResponse> recover(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID eventId,
      @Valid @RequestBody RentalInquiryOutboxRecoveryRequest request) {
    access.requireRentalInquiryOutboxRecoveryAdministrator(jwt);
    RentalInquiryBookedOutboxStore.RecoveryResult result =
        outbox.recover(
            eventId, request.expectedRecoveryVersion(), access.subjectId(jwt), request.reason());
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(
        new RentalInquiryOutboxRecoveryResponse(
            result.eventId(),
            result.status(),
            result.attemptCount(),
            result.recoveryVersion(),
            result.lastErrorCode(),
            result.reviewedBySubjectId(),
            result.reason(),
            result.reviewedAt()));
  }

  /** Keeps semantic review validation at the contract's 422 boundary for this operation. */
  @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class})
  public ProblemDetail invalidReview(Exception exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.UNPROCESSABLE_CONTENT, "Rental inquiry outbox review is invalid");
    problem.setType(URI.create("urn:rwms:problem:logistics:recovery-validation-failed"));
    problem.setTitle(HttpStatus.UNPROCESSABLE_CONTENT.getReasonPhrase());
    problem.setProperty("code", "LOGISTICS_RECOVERY_VALIDATION_FAILED");
    return problem;
  }
}
