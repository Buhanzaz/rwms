package dev.buhanzaz.rwms.logistics.order.recovery;

import dev.buhanzaz.rwms.logistics.order.recovery.RentalOrderMutationData.EquipmentReservations;
import dev.buhanzaz.rwms.logistics.order.recovery.RentalOrderMutationData.Intent;
import dev.buhanzaz.rwms.logistics.order.recovery.RentalOrderMutationData.ReleasedUnits;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Serializes only normalized order-mutation intent and receipt records for durable recovery. */
@Component
@RequiredArgsConstructor
public class RentalOrderMutationCodec {
  private final ObjectMapper objectMapper;

  /** Encodes one frozen mutation intent. */
  public String encode(Intent value) {
    return write(value);
  }

  /** Encodes one proven unit-release receipt. */
  public String encode(ReleasedUnits value) {
    return write(value);
  }

  /** Encodes one proven furniture-release receipt. */
  public String encode(EquipmentReservations value) {
    return write(value);
  }

  /** Decodes a stored frozen mutation intent and rejects corrupt local state. */
  public Intent intent(String value) {
    return read(value, Intent.class);
  }

  /** Decodes a stored unit-release receipt and rejects corrupt local state. */
  public ReleasedUnits releasedUnits(String value) {
    return read(value, ReleasedUnits.class);
  }

  /** Decodes a stored furniture-release receipt and rejects corrupt local state. */
  public EquipmentReservations equipmentReservations(String value) {
    return read(value, EquipmentReservations.class);
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Rental-order mutation state cannot be serialized", exception);
    }
  }

  private <T> T read(String value, Class<T> type) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Stored rental-order mutation state is missing");
    }
    try {
      return objectMapper.readValue(value, type);
    } catch (JacksonException | IllegalArgumentException exception) {
      throw new IllegalStateException("Stored rental-order mutation state is invalid", exception);
    }
  }
}
