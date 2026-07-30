package dev.buhanzaz.rwms.logistics.inquiry.api;

import static dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.*;

import dev.buhanzaz.rwms.logistics.inquiry.service.ClientPresentationService;
import dev.buhanzaz.rwms.logistics.inquiry.service.RentalBookingAlertService;
import dev.buhanzaz.rwms.logistics.inquiry.service.RentalInquiryService;
import dev.buhanzaz.rwms.logistics.inquiry.service.RentalSettingsService;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/logistics/v1")
@RequiredArgsConstructor
public class RentalInquiryController {
  private final RentalInquiryService inquiries;
  private final ClientPresentationService presentations;
  private final RentalBookingAlertService bookingAlerts;
  private final RentalSettingsService settings;
  private final OrderAuthorizer access;

  @PostMapping("/rental-inquiries")
  public ResponseEntity<RentalInquiryResponse> create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CreateRentalInquiryRequest request) {
    return ResponseEntity.status(201)
        .body(inquiries.create(access.writeActor(jwt), idempotencyKey, request));
  }

  @GetMapping("/rental-inquiries/{inquiryId}")
  public RentalInquiryResponse get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID inquiryId) {
    return inquiries.get(access.readActor(jwt), inquiryId);
  }

  @GetMapping("/rental-inquiries/{inquiryId}/cabin-facets")
  public CabinFacetsResponse facets(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID inquiryId) {
    return inquiries.facets(access.readActor(jwt), inquiryId);
  }

  @PostMapping("/rental-inquiries/{inquiryId}/cabin-searches")
  public CabinSearchResponse search(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID inquiryId,
      @Valid @RequestBody CabinSearchRequest request) {
    return inquiries.search(access.readActor(jwt), inquiryId, request);
  }

  @PostMapping("/rental-inquiries/{inquiryId}/cabin-availability")
  public CabinAvailabilityResponse availability(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID inquiryId,
      @Valid @RequestBody CabinAvailabilityRequest request) {
    return inquiries.availability(access.readActor(jwt), inquiryId, request);
  }

  @PutMapping("/rental-inquiries/{inquiryId}/client-presentation")
  public ClientPresentationResponse publish(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID inquiryId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody PublishClientPresentationRequest request) {
    return presentations.publish(
        access.writeActor(jwt), inquiryId, idempotencyKey, request);
  }

  @GetMapping("/rental-inquiries/{inquiryId}/client-presentation")
  public ClientPresentationResponse presentation(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID inquiryId) {
    return presentations.get(access.readActor(jwt), inquiryId);
  }

  @DeleteMapping("/rental-inquiries/{inquiryId}/client-presentation")
  public ResponseEntity<Void> revoke(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID inquiryId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey) {
    presentations.revoke(access.writeActor(jwt), inquiryId, idempotencyKey);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/rental-booking-alerts")
  public List<RentalBookingAlertResponse> bookingAlerts(@AuthenticationPrincipal Jwt jwt) {
    return bookingAlerts.list(access.readActor(jwt));
  }

  @PostMapping("/rental-booking-alerts/{bookingId}/actions")
  public ResponseEntity<Void> actOnBookingAlert(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID bookingId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody RentalBookingAlertActionRequest request) {
    bookingAlerts.act(access.writeActor(jwt), bookingId, idempotencyKey, request);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/settings/rental")
  public RentalSettingsResponse settings(@AuthenticationPrincipal Jwt jwt) {
    return settings.get(access.readActor(jwt));
  }

  @PutMapping("/settings/rental")
  public RentalSettingsResponse updateSettings(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody UpdateRentalSettingsRequest request) {
    return settings.update(access.writeActor(jwt), request);
  }
}
