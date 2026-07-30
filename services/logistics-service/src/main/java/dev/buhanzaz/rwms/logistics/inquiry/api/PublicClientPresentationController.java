package dev.buhanzaz.rwms.logistics.inquiry.api;

import static dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.*;

import dev.buhanzaz.rwms.logistics.inquiry.domain.PresentationBookingState;
import dev.buhanzaz.rwms.logistics.inquiry.service.ClientPresentationService;
import dev.buhanzaz.rwms.logistics.inquiry.service.PresentationBookingService;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/logistics/public/v1/client-presentations")
@RequiredArgsConstructor
public class PublicClientPresentationController {
  private final ClientPresentationService presentations;
  private final PresentationBookingService bookings;

  @GetMapping("/{token}")
  public ResponseEntity<PublicClientPresentationResponse> get(
      @PathVariable @Size(min = 40, max = 256) String token) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(presentations.publicPresentation(token));
  }

  @PostMapping("/{token}/bookings")
  public ResponseEntity<PresentationBookingResponse> confirm(
      @PathVariable @Size(min = 40, max = 256) String token,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody ConfirmClientPresentationRequest request) {
    PresentationBookingResponse response =
        bookings.confirm(token, idempotencyKey, request);
    return bookingResponse(response);
  }

  @GetMapping("/{token}/bookings/{bookingId}")
  public ResponseEntity<PresentationBookingResponse> status(
      @PathVariable @Size(min = 40, max = 256) String token,
      @PathVariable UUID bookingId) {
    return bookingResponse(bookings.status(token, bookingId));
  }

  @GetMapping(
      value = "/{token}/media/{cabinId}/{mediaId}/{generation}/{variant}",
      produces = "image/webp")
  public ResponseEntity<byte[]> media(
      @PathVariable @Size(min = 40, max = 256) String token,
      @PathVariable UUID cabinId,
      @PathVariable UUID mediaId,
      @PathVariable @Min(1) long generation,
      @PathVariable String variant) {
    LogisticsDependencyGateway.MediaContent content =
        presentations.media(token, cabinId, mediaId, generation, variant);
    MediaType type;
    try {
      type = MediaType.parseMediaType(content.contentType());
    } catch (IllegalArgumentException exception) {
      type = MediaType.APPLICATION_OCTET_STREAM;
    }
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(type)
        .header("X-Content-Type-Options", "nosniff")
        .body(content.bytes());
  }

  private static ResponseEntity<PresentationBookingResponse> bookingResponse(
      PresentationBookingResponse response) {
    HttpStatus status =
        switch (PresentationBookingState.valueOf(response.state())) {
          case PENDING -> HttpStatus.ACCEPTED;
          case COMPLETED -> HttpStatus.CREATED;
          case REJECTED -> HttpStatus.CONFLICT;
        };
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .body(response);
  }
}
