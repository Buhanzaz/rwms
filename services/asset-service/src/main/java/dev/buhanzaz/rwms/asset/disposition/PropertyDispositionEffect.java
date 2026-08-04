package dev.buhanzaz.rwms.asset.disposition;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.type.SqlTypes;

/** Immutable persisted response for an effect replay after ordinary idempotency retention ends. */
@Entity
@Table(name = "property_disposition_effect")
public class PropertyDispositionEffect {
  @Id
  @Column(name = "effect_id", nullable = false)
  private UUID effectId;

  @Column(name = "decision_id", nullable = false)
  private UUID decisionId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "response_body", nullable = false, columnDefinition = "jsonb")
  private String responseBody;

  @Column(name = "response_sha256", nullable = false, length = 64)
  private String responseSha256;

  @Column(name = "applied_at", nullable = false)
  private OffsetDateTime appliedAt;

  protected PropertyDispositionEffect() {}

  public static PropertyDispositionEffect record(
      UUID effectId,
      UUID decisionId,
      String responseBody,
      String responseSha256,
      OffsetDateTime appliedAt) {
    if (effectId == null
        || decisionId == null
        || responseBody == null
        || responseSha256 == null
        || !responseSha256.matches("[0-9a-f]{64}")
        || appliedAt == null) {
      throw new IllegalArgumentException("Property disposition effect is invalid");
    }
    PropertyDispositionEffect value = new PropertyDispositionEffect();
    value.effectId = effectId;
    value.decisionId = decisionId;
    value.responseBody = responseBody;
    value.responseSha256 = responseSha256;
    value.appliedAt = appliedAt;
    return value;
  }

  public UUID getEffectId() { return effectId; }
  public UUID getDecisionId() { return decisionId; }
  public String getResponseBody() { return responseBody; }
  public String getResponseSha256() { return responseSha256; }
  public OffsetDateTime getAppliedAt() { return appliedAt; }

  @Override
  public final boolean equals(Object other) {
    if (this == other) return true;
    if (other == null) return false;
    Class<?> otherClass = other instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass()
        : other.getClass();
    Class<?> thisClass = this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass()
        : getClass();
    return thisClass == otherClass
        && effectId != null
        && Objects.equals(effectId, ((PropertyDispositionEffect) other).effectId);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
