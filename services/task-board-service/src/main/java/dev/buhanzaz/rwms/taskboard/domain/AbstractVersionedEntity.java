package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.proxy.HibernateProxy;

@MappedSuperclass
public abstract class AbstractVersionedEntity {
  @Id
  @AssignedOrGeneratedUuid
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  public UUID getId() {
    return id;
  }

  public long getVersion() {
    return version;
  }

  public final void assignReviewedId(UUID reviewedId) {
    if (id != null) {
      throw new IllegalStateException("Entity identity is already assigned");
    }
    id = Objects.requireNonNull(reviewedId, "reviewedId");
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
    if (thisClass != otherClass) return false;
    var entity = (AbstractVersionedEntity) other;
    return id != null && Objects.equals(id, entity.id);
  }

  @Override
  public final int hashCode() {
    return this instanceof HibernateProxy proxy
        ? proxy.getHibernateLazyInitializer().getPersistentClass().hashCode()
        : getClass().hashCode();
  }
}
