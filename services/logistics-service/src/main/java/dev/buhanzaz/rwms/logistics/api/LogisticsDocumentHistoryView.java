package dev.buhanzaz.rwms.logistics.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Warehouse-authorized history of one return or shipment. Equipment snapshots retain their original
 * workflow meaning; missing snapshots are null, never an assertion that the cabin was empty.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record LogisticsDocumentHistoryView(
    UUID documentId,
    UUID warehouseId,
    LogisticsDocumentType documentType,
    long documentVersion,
    List<Line> lines,
    List<Event> events,
    Long nextAfterVersion) {

  /**
   * Saved equipment-only evidence. Before-operation contents precede preparation/intake;
   * after-registration contents are ledger evidence, not a physical inspection. Return acceptance
   * records the operator's submitted confirmation and extras, not completion of the saga.
   */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Line(
      UUID lineId,
      UUID assetId,
      JsonNode contentsBeforeOperation,
      JsonNode contentsAfterRegistration,
      JsonNode returnAcceptance,
      JsonNode inventoryShipmentFurniture) {}

  /**
   * Ordered journal attribution, not necessarily the physical executor. Background completion can
   * retain the document creator; acceptance-started identifies the actual acceptance command actor.
   * Baseline facts have no occurrence time and must not be presented as a new physical operation.
   */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Event(
      UUID eventId,
      long aggregateVersion,
      String eventType,
      OffsetDateTime occurredAt,
      OffsetDateTime recordedAt,
      boolean baseline,
      OpaqueActorReference recordedActor,
      LogisticsDocumentState state,
      String resultCode) {}
}
