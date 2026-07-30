package dev.buhanzaz.rwms.assistant.integration;

import dev.buhanzaz.rwms.assistant.api.AssistantApiModels;
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

  JsonNode searchAvailableCabins(
      UUID rentalInquiryId, CabinSearch search, String bearerToken);

  /**
   * Reads the current client presentation for the inquiry. A missing presentation is a normal
   * state, not an upstream error.
   */
  Optional<ClientPresentation> findClientPresentation(
      UUID rentalInquiryId, String bearerToken);

  record InquiryBootstrap(
      UUID rentalInquiryId,
      UUID clientId,
      String inquiryStatus,
      String clientType,
      String clientDisplayName) {}

  record ClientPresentation(UUID inquiryId, String state) {
    public ClientPresentation {
      if (inquiryId == null || state == null || state.isBlank()) {
        throw new IllegalArgumentException("Client presentation identity and state are required");
      }
    }
  }

  record CabinSearch(List<CabinSearchGroup> groups, UUID warehouseId) {
    public CabinSearch {
      if (groups == null || groups.isEmpty() || groups.size() > 20) {
        throw new IllegalArgumentException("A cabin search must contain from one to twenty groups");
      }
      if (warehouseId == null) {
        throw new IllegalArgumentException("warehouseId is required for a cabin search");
      }
      groups = List.copyOf(groups);
    }
  }

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
}
