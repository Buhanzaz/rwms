package dev.buhanzaz.rwms.logistics.order.service;

import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningDateOption;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Produces the deterministic revision of one complete route-planning source snapshot. */
final class PlanningRequestRevision {
  private PlanningRequestRevision() {}

  /**
   * Hashes every exported planning fact, including facts owned outside the rental-order aggregate.
   * The order version remains a separate optimistic command fence.
   */
  static String sha256(
      RentalOrder order,
      List<UUID> availableUnitIds,
      List<PlanningDateOption> dateOptions,
      Boolean trailerAccessAllowed,
      Long deliveryPriceRubles,
      Integer priceIsochroneMinutes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      append(digest, "RWMS_PLANNING_REQUEST_V2");
      appendNullable(digest, order.getId());
      appendNullable(digest, order.getVersion());
      appendNullable(digest, order.getOrderNumber());
      appendNullable(
          digest, order.getClient() == null ? null : order.getClient().getDisplayName());
      appendNullable(digest, order.getDeliveryAddress());
      appendNullable(digest, decimal(order.getLatitude()));
      appendNullable(digest, decimal(order.getLongitude()));
      append(digest, Integer.toString(availableUnitIds.size()));
      for (UUID unitId : availableUnitIds) appendNullable(digest, unitId);
      append(digest, Integer.toString(dateOptions.size()));
      for (PlanningDateOption option : dateOptions) {
        appendNullable(digest, option.date());
        appendNullable(digest, option.priority());
        appendNullable(digest, option.isHard());
        appendNullable(digest, option.windowStart());
        appendNullable(digest, option.windowEnd());
        appendNullable(digest, option.travelZoneHours());
      }
      appendNullable(digest, trailerAccessAllowed);
      appendNullable(digest, deliveryPriceRubles);
      appendNullable(digest, priceIsochroneMinutes);
      appendNullable(digest, instant(order.getCreatedAt()));
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String decimal(BigDecimal value) {
    return value == null ? null : value.stripTrailingZeros().toPlainString();
  }

  private static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }

  private static void appendNullable(MessageDigest digest, Object value) {
    if (value == null) {
      digest.update((byte) 0);
      return;
    }
    digest.update((byte) 1);
    append(digest, value.toString());
  }

  private static void append(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update((byte) (bytes.length >>> 24));
    digest.update((byte) (bytes.length >>> 16));
    digest.update((byte) (bytes.length >>> 8));
    digest.update((byte) bytes.length);
    digest.update(bytes);
  }
}
