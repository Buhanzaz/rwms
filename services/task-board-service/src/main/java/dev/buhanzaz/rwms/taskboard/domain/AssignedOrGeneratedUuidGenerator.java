package dev.buhanzaz.rwms.taskboard.domain;

import java.util.EnumSet;
import java.util.UUID;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.BeforeExecutionGenerator;
import org.hibernate.generator.EventType;
import org.hibernate.generator.EventTypeSets;

/** Preserves a caller-assigned UUID for idempotent creates or generates one before first insert. */
public final class AssignedOrGeneratedUuidGenerator implements BeforeExecutionGenerator {
  @Override
  public Object generate(
      SharedSessionContractImplementor session,
      Object owner,
      Object currentValue,
      EventType eventType) {
    return currentValue == null ? UUID.randomUUID() : currentValue;
  }

  @Override
  public EnumSet<EventType> getEventTypes() {
    return EventTypeSets.INSERT_ONLY;
  }

  @Override
  public Class<?> getGeneratedType() {
    return UUID.class;
  }

  @Override
  public boolean allowAssignedIdentifiers() {
    return true;
  }
}
