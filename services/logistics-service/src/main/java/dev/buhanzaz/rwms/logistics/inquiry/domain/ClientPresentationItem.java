package dev.buhanzaz.rwms.logistics.inquiry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.proxy.HibernateProxy;

@Entity
@Table(name = "client_presentation_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClientPresentationItem {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "presentation_id", nullable = false)
  private UUID presentationId;

  @Column(name = "presentation_revision", nullable = false)
  private long presentationRevision;

  @Column(name = "rental_item_id", nullable = false)
  private UUID rentalItemId;

  @Column(name = "group_key", nullable = false, length = 128)
  private String groupKey;

  @Column(name = "group_label", nullable = false, length = 255)
  private String groupLabel;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "cabin_snapshot_json", nullable = false, columnDefinition = "jsonb")
  private String cabinSnapshotJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "media_snapshot_json", nullable = false, columnDefinition = "jsonb")
  private String mediaSnapshotJson;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  public static ClientPresentationItem create(
      UUID presentationId,
      long presentationRevision,
      UUID rentalItemId,
      String groupKey,
      String groupLabel,
      int sortOrder,
      String cabinSnapshotJson,
      String mediaSnapshotJson,
      OffsetDateTime now) {
    if (presentationRevision < 1 || sortOrder < 0) {
      throw new IllegalArgumentException("Presentation item order is invalid");
    }
    ClientPresentationItem item = new ClientPresentationItem();
    item.presentationId = Objects.requireNonNull(presentationId, "presentationId");
    item.presentationRevision = presentationRevision;
    item.rentalItemId = Objects.requireNonNull(rentalItemId, "rentalItemId");
    item.groupKey = requireText(groupKey, 128, "groupKey");
    item.groupLabel = requireText(groupLabel, 255, "groupLabel");
    item.sortOrder = sortOrder;
    item.cabinSnapshotJson = requireJson(cabinSnapshotJson, "cabinSnapshotJson");
    item.mediaSnapshotJson = requireJson(mediaSnapshotJson, "mediaSnapshotJson");
    item.createdAt = Objects.requireNonNull(now, "now");
    return item;
  }

  private static String requireText(String value, int maximum, String field) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > maximum) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }

  private static String requireJson(String value, String field) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > 64_000) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }

  @Override
  public final boolean equals(Object other) {
    if (this == other) return true;
    if (other == null) return false;
    Class<?> otherClass =
        other instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass()
            : other.getClass();
    Class<?> thisClass =
        this instanceof HibernateProxy proxy
            ? proxy.getHibernateLazyInitializer().getPersistentClass()
            : getClass();
    return thisClass == otherClass
        && id != null
        && Objects.equals(id, ((ClientPresentationItem) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
