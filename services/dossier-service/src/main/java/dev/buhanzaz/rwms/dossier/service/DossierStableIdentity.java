package dev.buhanzaz.rwms.dossier.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/** Replay-stable UUIDv5 identities for activities and outbound facts. */
public final class DossierStableIdentity {
  private static final UUID NAMESPACE =
      UUID.fromString("3d55f1ca-c06d-5bd8-88ad-8947f95db4fd");

  private DossierStableIdentity() {}

  public static UUID activity(UUID sourceEventId, UUID cabinId) {
    return uuid5("dossier-activity-v1:" + sourceEventId + ":" + cabinId);
  }

  public static UUID outbound(UUID sourceEventId, UUID cabinId) {
    return uuid5("dossier-cabin-activity-v1:" + sourceEventId + ":" + cabinId);
  }

  public static UUID uuid5(String name) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-1");
      digest.update(uuidBytes(NAMESPACE));
      byte[] bytes = digest.digest(name.getBytes(StandardCharsets.UTF_8));
      bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x50);
      bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      return new UUID(buffer.getLong(), buffer.getLong());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-1 is unavailable", exception);
    }
  }

  private static byte[] uuidBytes(UUID value) {
    return ByteBuffer.allocate(16)
        .putLong(value.getMostSignificantBits())
        .putLong(value.getLeastSignificantBits())
        .array();
  }
}
