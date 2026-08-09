package dev.buhanzaz.rwms.logistics.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.proxy.HibernateProxy;

/**
 * Logistics-owned rental client with normalized contact projections and an authenticated
 * responsible-manager snapshot.
 */
@Entity
@Table(name = "order_client")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderClient {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Enumerated(EnumType.STRING)
  @Column(name = "client_type", nullable = false, length = 32)
  private ClientType clientType;

  @Column(name = "display_name", nullable = false, length = 512)
  private String displayName;

  @Column(name = "normalized_name", nullable = false, length = 512)
  private String normalizedName;

  @Column(name = "phone", length = 32)
  private String phone;

  @Column(name = "normalized_phone", length = 32)
  private String normalizedPhone;

  @Column(name = "email", length = 320)
  private String email;

  @Column(name = "normalized_email", length = 320)
  private String normalizedEmail;

  @Column(name = "contact_person", length = 255)
  private String contactPerson;

  @Column(name = "responsible_manager_id", nullable = false)
  private UUID responsibleManagerId;

  @Column(name = "responsible_manager_display_name", length = 255)
  private String responsibleManagerDisplayName;

  @Column(name = "comment", length = 2_000)
  private String comment;

  @Column(name = "source", length = 255)
  private String source;

  @Column(name = "created_by_subject_id", nullable = false)
  private UUID createdBySubjectId;

  @Column(name = "creation_idempotency_key", nullable = false)
  private UUID creationIdempotencyKey;

  @JdbcTypeCode(Types.CHAR)
  @Column(name = "creation_request_sha256", nullable = false, length = 64)
  private String creationRequestSha256;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  /**
   * Creates a client owned by the authenticated responsible manager.
   *
   * <p>A contact person is mandatory for an individual entrepreneur or legal entity. The phone
   * projection is mandatory for every new client even though historical rows created before V42
   * can still contain no phone.</p>
   */
  public static OrderClient create(
      ClientType clientType,
      String displayName,
      String normalizedName,
      String phone,
      String normalizedPhone,
      String email,
      String normalizedEmail,
      String contactPerson,
      UUID responsibleManagerId,
      String responsibleManagerDisplayName,
      String comment,
      String source,
      UUID actorSubjectId,
      UUID idempotencyKey,
      String requestSha256) {
    OrderClient client = new OrderClient();
    client.clientType = Objects.requireNonNull(clientType, "clientType");
    client.displayName = requireText(displayName, 512, "displayName");
    client.normalizedName = requireText(normalizedName, 512, "normalizedName");
    client.phone = requireText(phone, 32, "phone");
    client.normalizedPhone = requireText(normalizedPhone, 32, "normalizedPhone");
    client.email = optionalText(email, 320, "email");
    client.normalizedEmail = optionalText(normalizedEmail, 320, "normalizedEmail");
    if ((client.email == null) != (client.normalizedEmail == null)) {
      throw new IllegalArgumentException("email projection is invalid");
    }
    client.contactPerson = optionalText(contactPerson, 255, "contactPerson");
    if (client.clientType != ClientType.INDIVIDUAL && client.contactPerson == null) {
      throw new IllegalArgumentException("contactPerson is required for this client type");
    }
    client.responsibleManagerId =
        Objects.requireNonNull(responsibleManagerId, "responsibleManagerId");
    client.responsibleManagerDisplayName =
        requireText(responsibleManagerDisplayName, 255, "responsibleManagerDisplayName");
    client.comment = optionalText(comment, 2_000, "comment");
    client.source = optionalText(source, 255, "source");
    client.createdBySubjectId = Objects.requireNonNull(actorSubjectId, "actorSubjectId");
    client.creationIdempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    client.creationRequestSha256 = requireHash(requestSha256);
    client.createdAt = now();
    client.updatedAt = client.createdAt;
    return client;
  }

  public boolean matchesCreationRequest(String requestSha256) {
    return creationRequestSha256.equals(requestSha256);
  }

  private static String requireText(String value, int maximum, String field) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > maximum) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }

  private static String requireHash(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("requestSha256 is invalid");
    }
    return value;
  }

  private static String optionalText(String value, int maximum, String field) {
    if (value == null) return null;
    String normalized = value.trim();
    if (normalized.isEmpty() || normalized.length() > maximum) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
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
        && Objects.equals(id, ((OrderClient) other).id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
