package dev.buhanzaz.rwms.logistics.customer.api;

import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.*;

import dev.buhanzaz.rwms.logistics.customer.api.CustomerBookingChangeApiModels.CreateCustomerBookingChangeQuoteRequest;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerBookingChangeApiModels.CustomerBookingChangeQuoteResponse;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerAuthorizer;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerBookingChangeService;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerBookingLifecycleService;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerBookingService;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerCabinCatalogService;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerCheckoutService;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerDeliverySlotService;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerProfileService;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerRentalService;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerWarehouseService;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.CabinFacetsResponse;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated public HTTP boundary used only by the dedicated Android CustomerApp. */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/logistics/customer/v1")
public class CustomerController {
  private final CustomerAuthorizer access;
  private final CustomerProfileService profiles;
  private final CustomerWarehouseService warehouses;
  private final CustomerRentalService rentals;
  private final CustomerCabinCatalogService catalog;
  private final CustomerDeliverySlotService deliverySlots;
  private final CustomerCheckoutService checkout;
  private final CustomerBookingService customerBookings;
  private final CustomerBookingLifecycleService bookingLifecycle;
  private final CustomerBookingChangeService bookingChanges;

  /** Returns the logistics profile of the authenticated customer. */
  @GetMapping("/profile")
  public CustomerProfileResponse profile(@AuthenticationPrincipal Jwt jwt) {
    return profiles.get(identity(jwt));
  }

  /** Creates the one-time logistics profile and rental client projection. */
  @PostMapping("/profile")
  public ResponseEntity<CustomerProfileResponse> createProfile(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody CustomerProfileRequest request) {
    return ResponseEntity.status(201).body(profiles.create(identity(jwt), request));
  }

  /** Updates mutable contact and display fields under the profile version fence. */
  @PutMapping("/profile")
  public CustomerProfileResponse updateProfile(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody UpdateCustomerProfileRequest request) {
    return profiles.update(identity(jwt), request);
  }

  /** Establishes the exact subject-bound media scope used for profile-avatar upload. */
  @PostMapping("/profile/avatar-upload")
  public CustomerProfileAvatarUploadScope prepareProfileAvatarUpload(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody PrepareCustomerProfileAvatarUploadRequest request) {
    return profiles.prepareAvatarUpload(identity(jwt), request);
  }

  /** Binds one READY avatar generation after private media ownership validation. */
  @PutMapping("/profile/avatar")
  public CustomerProfileResponse setProfileAvatar(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody SetCustomerProfileAvatarRequest request) {
    return profiles.setAvatar(identity(jwt), request);
  }

  /** Lists active routable representative and explicitly enabled ordinary warehouses. */
  @GetMapping("/warehouses")
  public List<CustomerWarehouseResponse> warehouses(@AuthenticationPrincipal Jwt jwt) {
    identity(jwt);
    return warehouses.list();
  }

  /** Starts a customer-owned rental inquiry and cart. */
  @PostMapping("/inquiries")
  public ResponseEntity<CustomerInquiryResponse> createInquiry(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CreateCustomerInquiryRequest request) {
    return ResponseEntity.status(201)
        .body(rentals.createInquiry(identity(jwt), idempotencyKey, request));
  }

  /** Returns one customer-owned inquiry/cart version. */
  @GetMapping("/inquiries/{inquiryId}")
  public CustomerInquiryResponse inquiry(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID inquiryId) {
    return rentals.inquiry(identity(jwt), inquiryId);
  }

  /** Returns exact available warehouse facets for the selected inquiry. */
  @GetMapping("/inquiries/{inquiryId}/facets")
  public CabinFacetsResponse facets(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID inquiryId) {
    return rentals.facets(identity(jwt), inquiryId);
  }

  /** Returns one bounded page of currently bookable cabin cards. */
  @GetMapping("/inquiries/{inquiryId}/cabins")
  public CustomerCabinPage cabins(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID inquiryId,
      @RequestParam(required = false) @Size(max = 255) String query,
      @RequestParam(required = false) @Size(max = 255) String cabinType,
      @RequestParam(required = false) @Size(max = 255) String finish,
      @RequestParam(required = false) @Size(max = 255) String dimensions,
      @RequestParam(required = false) @Size(max = 255) String category,
      @RequestParam(required = false) Boolean linoleum,
      @RequestParam(required = false) @Size(max = 20) List<@Size(max = 255) String> characteristics,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return catalog.page(
        identity(jwt),
        inquiryId,
        query,
        cabinType,
        finish,
        dimensions,
        category,
        linoleum,
        characteristics,
        page,
        size);
  }

  /** Returns current cabin holds without extending their lifetime. */
  @GetMapping("/inquiries/{inquiryId}/selection")
  public CustomerCabinSelectionResponse selection(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID inquiryId) {
    return rentals.cabinSelection(identity(jwt), inquiryId);
  }

  /** Replaces the complete cabin hold set under idempotency and cart version fences. */
  @PutMapping("/inquiries/{inquiryId}/selection")
  public CustomerCabinSelectionResponse replaceSelection(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID inquiryId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody ReplaceCustomerCabinsRequest request) {
    return rentals.replaceCabins(identity(jwt), inquiryId, idempotencyKey, request);
  }

  /** Lists furniture positions that currently have positive warehouse stock. */
  @GetMapping("/inquiries/{inquiryId}/equipment")
  public List<CustomerEquipmentAvailability> equipment(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID inquiryId) {
    return rentals.equipment(identity(jwt), inquiryId);
  }

  /** Replaces the complete per-cabin furniture intent under the cart version fence. */
  @PutMapping("/inquiries/{inquiryId}/equipment")
  public CustomerEquipmentSelectionResponse replaceEquipment(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID inquiryId,
      @Valid @RequestBody ReplaceCustomerEquipmentRequest request) {
    return rentals.replaceEquipment(identity(jwt), inquiryId, request);
  }

  /** Replaces the complete per-cabin initial rental durations under the cart fence. */
  @PutMapping("/inquiries/{inquiryId}/rental-terms")
  public CustomerRentalTermsResponse replaceRentalTerms(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID inquiryId,
      @Valid @RequestBody ReplaceCustomerRentalTermsRequest request) {
    return rentals.replaceRentalTerms(identity(jwt), inquiryId, request);
  }

  /** Returns the complete current cart projection. */
  @GetMapping("/inquiries/{inquiryId}/cart")
  public CustomerCartResponse cart(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID inquiryId) {
    return rentals.cart(identity(jwt), inquiryId);
  }

  /** Streams one authorized current cabin photo without exposing media-service. */
  @GetMapping("/inquiries/{inquiryId}/cabins/{cabinId}/photos/{mediaId}")
  public ResponseEntity<byte[]> photo(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID inquiryId,
      @PathVariable UUID cabinId,
      @PathVariable UUID mediaId,
      @RequestParam @Min(1) long generation,
      @RequestParam String variant) {
    LogisticsDependencyGateway.MediaContent content =
        catalog.media(identity(jwt), inquiryId, cabinId, mediaId, generation, variant);
    MediaType type = MediaType.parseMediaType(content.contentType());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .contentType(type)
        .contentLength(content.bytes().length)
        .body(content.bytes());
  }

  /** Calculates and persists only currently feasible delivery offers. */
  @PostMapping("/delivery-slots/search")
  public List<CustomerDeliverySlotResponse> searchDeliverySlots(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody DeliverySlotSearchRequest request) {
    return deliverySlots.search(identity(jwt), request);
  }

  /** Rechecks route capacity and holds one selected offer. */
  @PostMapping("/delivery-slots/{slotId}/hold")
  public HeldCustomerDeliverySlotResponse holdDeliverySlot(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID slotId,
      @RequestParam @Min(0) long expectedSlotVersion,
      @Valid @RequestBody HoldCustomerDeliverySlotRequest request) {
    return deliverySlots.hold(identity(jwt), slotId, expectedSlotVersion, request);
  }

  /** Converts the held cabins/furniture and route slot into one saved real rental order. */
  @PostMapping("/inquiries/{inquiryId}/checkout")
  public CustomerBookingResponse checkout(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID inquiryId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CustomerCheckoutRequest request) {
    return checkout.checkout(identity(jwt), inquiryId, idempotencyKey, request);
  }

  /** Lists current customer's durable booking outcomes. */
  @GetMapping("/bookings")
  public List<CustomerBookingResponse> bookings(@AuthenticationPrincipal Jwt jwt) {
    return checkout.bookings(identity(jwt));
  }

  /** Quotes an exact booking change without reserving capacity or starting the change. */
  @PostMapping("/bookings/{bookingId}/change-quotes")
  public CustomerBookingChangeQuoteResponse quoteBookingChange(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID bookingId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CreateCustomerBookingChangeQuoteRequest request) {
    return bookingChanges.create(identity(jwt), bookingId, idempotencyKey, request);
  }

  /** Returns the exact settlement/application result after a lost response or app restart. */
  @GetMapping("/bookings/{bookingId}/change-quotes/{quoteId}")
  public CustomerBookingChangeQuoteResponse bookingChangeQuote(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID bookingId, @PathVariable UUID quoteId) {
    return bookingChanges.get(identity(jwt), bookingId, quoteId);
  }

  /** Cancels an untouched completed booking using an explicitly accepted fee quote. */
  @PostMapping("/bookings/{bookingId}/cancel")
  public CustomerBookingResponse cancelBooking(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID bookingId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CancelCustomerBookingRequest request) {
    return bookingLifecycle.cancel(identity(jwt), bookingId, idempotencyKey, request);
  }

  /** Searches delivery-slot replacements using the booked order's exact immutable contents. */
  @PostMapping("/bookings/{bookingId}/delivery-slots/search")
  public List<CustomerDeliverySlotResponse> searchBookingRescheduleSlots(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID bookingId,
      @Valid @RequestBody SearchCustomerBookingRescheduleRequest request) {
    return deliverySlots.searchBooking(identity(jwt), bookingId, request);
  }

  /** Atomically replaces one booking's confirmed slot under idempotency and version fences. */
  @PostMapping("/bookings/{bookingId}/reschedule")
  public CustomerBookingResponse rescheduleBooking(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID bookingId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody RescheduleCustomerBookingRequest request) {
    return bookingLifecycle.reschedule(identity(jwt), bookingId, idempotencyKey, request);
  }

  /** Accepts one arrived cabin with the customer's full-screen drawn signature. */
  @PostMapping("/bookings/{bookingId}/cabins/{cabinId}/acceptance")
  public CustomerCabinAcceptanceResponse acceptCabin(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID bookingId,
      @PathVariable UUID cabinId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody AcceptCustomerCabinRequest request) {
    return customerBookings.accept(identity(jwt), bookingId, cabinId, idempotencyKey, request);
  }

  /** Records one arrived-cabin problem with optional ready in-app media evidence. */
  @PostMapping("/bookings/{bookingId}/cabins/{cabinId}/problems")
  public ResponseEntity<CustomerCabinProblemResponse> reportCabinProblem(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID bookingId,
      @PathVariable UUID cabinId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody ReportCustomerCabinProblemRequest request) {
    return ResponseEntity.status(201)
        .body(
            customerBookings.reportProblem(
                identity(jwt), bookingId, cabinId, idempotencyKey, request));
  }

  private CustomerIdentity identity(Jwt jwt) {
    return access.identity(jwt);
  }
}
