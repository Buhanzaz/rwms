package dev.buhanzaz.rwms.architecture.fixture.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

public final class UnsafeBoundaryMappers {
  private UnsafeBoundaryMappers() {}

  @Mapper
  public interface SecuritySecretMapper {
    SafeDto toDto(PasswordHashSecret source);
  }

  @Mapper
  public interface OutboxMapper {
    OutboxEvent toOutbox(SafeProjection source);
  }

  @Mapper
  public interface DomainTransitionMapper {
    WorkQueueAggregate toAggregate(TransitionRequest source);
  }

  @Mapper
  public interface OptimisticVersionMutationMapper {
    void update(VersionCommand source, @MappingTarget SafeDto target);
  }

  public record SafeProjection(String id) {}

  public record SafeDto(String id) {}

  public record PasswordHashSecret(String value) {}

  public record OutboxEvent(String id) {}

  public record WorkQueueAggregate(String id) {}

  public record TransitionRequest(String id) {}

  public record VersionCommand(long expectedVersion) {}
}
