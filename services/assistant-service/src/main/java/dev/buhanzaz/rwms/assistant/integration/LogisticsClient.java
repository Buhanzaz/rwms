package dev.buhanzaz.rwms.assistant.integration;

import dev.buhanzaz.rwms.assistant.api.AssistantApiModels;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Private service-to-service boundary; browser traffic never uses this client. */
public interface LogisticsClient {
  InquiryBootstrap createRentalInquiry(
      UUID conversationId,
      UUID clientId,
      AssistantApiModels.NewClientRequest newClient,
      String bearerToken);

  JsonNode listAvailableCabinFacets(UUID rentalInquiryId, String bearerToken);

  /**
   * Executes one exact search attempt with the durable caller key for this request payload. The
   * adapter must not replace that key when handling an uncertain response.
   */
  JsonNode searchAvailableCabins(
      UUID rentalInquiryId,
      UUID idempotencyKey,
      CabinSearch search,
      String bearerToken);

  /** Reads the logistics-owned live inquiry selection without renewing any hold. */
  CabinSelection readCabinSelection(UUID rentalInquiryId, String bearerToken);

  /** Atomically keeps exactly the supplied IDs; an empty list releases the inquiry selection. */
  CabinSelection replaceCabinSelection(
      UUID rentalInquiryId,
      UUID idempotencyKey,
      UUID warehouseId,
      List<UUID> rentalItemIds,
      String bearerToken);

  /** Reads one bounded informational cabin fact page without acquiring a hold. */
  JsonNode lookupCabinCatalog(
      UUID rentalInquiryId,
      UUID warehouseId,
      String query,
      int page,
      int size,
      String bearerToken);

  /**
   * Reads the current client presentation for the inquiry. A missing presentation is a normal
   * state, not an upstream error.
   */
  Optional<ClientPresentation> findClientPresentation(
      UUID rentalInquiryId, String bearerToken);

  /** Identities and display metadata returned by idempotent inquiry creation. */
  record InquiryBootstrap(
      UUID rentalInquiryId,
      UUID clientId,
      String inquiryStatus,
      String clientType,
      String clientDisplayName) {}

  /** Existing published client presentation used to disable unsafe selection merging. */
  record ClientPresentation(UUID inquiryId, String state) {
    public ClientPresentation {
      if (inquiryId == null || state == null || state.isBlank()) {
        throw new IllegalArgumentException("Client presentation identity and state are required");
      }
    }
  }

  /** Explicit search mutation semantics; omission is always replacement-safe. */
  enum SearchResultMode {
    APPEND,
    REPLACE
  }

  /** Exact warehouse search and its explicit hold mutation mode. */
  record CabinSearch(
      List<CabinSearchGroup> groups, UUID warehouseId, SearchResultMode resultMode) {
    public CabinSearch(List<CabinSearchGroup> groups, UUID warehouseId) {
      this(groups, warehouseId, SearchResultMode.REPLACE);
    }

    public CabinSearch {
      if (groups == null || groups.isEmpty() || groups.size() > 20) {
        throw new IllegalArgumentException("A cabin search must contain from one to twenty groups");
      }
      if (warehouseId == null) {
        throw new IllegalArgumentException("warehouseId is required for a cabin search");
      }
      resultMode = resultMode == null ? SearchResultMode.REPLACE : resultMode;
      groups = List.copyOf(groups);
    }
  }

  /** One exact logistics availability group after assistant facet validation. */
  record CabinSearchGroup(
      String cabinType,
      String finish,
      String dimensions,
      String category,
      String characteristics,
      Boolean linoleum,
      int quantity) {
    public CabinSearchGroup {
      cabinType = validate(cabinType, "cabinType", 255);
      finish = validate(finish, "finish", 255);
      dimensions = validate(dimensions, "dimensions", 255);
      category = validate(category, "category", 255);
      characteristics = validate(characteristics, "characteristics", 2000);
      if (quantity < 1 || quantity > 30) {
        throw new IllegalArgumentException("quantity must be between 1 and 30");
      }
    }

    /** Preserve facet text exactly; the model must not normalize or reinterpret it. */
    private static String validate(String value, String field, int maximumLength) {
      if (value == null) return null;
      if (value.length() > maximumLength) {
        throw new IllegalArgumentException(field + " is too long");
      }
      return value;
    }
  }

  /** Authoritative held cabin selection returned by logistics-service. */
  record CabinSelection(
      UUID inquiryId,
      UUID warehouseId,
      OffsetDateTime expiresAt,
      List<UUID> rentalItemIds,
      List<JsonNode> items) {
    public CabinSelection {
      if (inquiryId == null || rentalItemIds == null || items == null) {
        throw new IllegalArgumentException("Cabin selection data is incomplete");
      }
      rentalItemIds = List.copyOf(rentalItemIds);
      items = items.stream().map(JsonNode::deepCopy).toList();
      if (rentalItemIds.isEmpty() != items.isEmpty()) {
        throw new IllegalArgumentException("Cabin selection IDs and items are inconsistent");
      }
      if (rentalItemIds.isEmpty() != (expiresAt == null)) {
        throw new IllegalArgumentException("Cabin selection expiry is inconsistent");
      }
      if (!rentalItemIds.isEmpty() && warehouseId == null) {
        throw new IllegalArgumentException("A non-empty cabin selection requires a warehouse");
      }
    }
  }
}
