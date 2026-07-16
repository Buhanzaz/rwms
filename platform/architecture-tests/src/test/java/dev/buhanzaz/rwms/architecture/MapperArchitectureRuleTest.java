package dev.buhanzaz.rwms.architecture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import dev.buhanzaz.rwms.architecture.fixture.mapper.SafeReadMapper;
import dev.buhanzaz.rwms.architecture.fixture.mapper.UnsafeBoundaryMappers;
import dev.buhanzaz.rwms.architecture.fixture.mapper.UnsafeMutationMapper;
import org.junit.jupiter.api.Test;

class MapperArchitectureRuleTest {
  @Test
  void acceptsReadProjectionMapper() {
    var classes = new ClassFileImporter().importClasses(SafeReadMapper.class);

    assertDoesNotThrow(
        () -> ArchitectureRules.MAPPERS_DO_NOT_MUTATE_ENTITIES_FROM_COMMANDS.check(classes));
    assertDoesNotThrow(
        () ->
            ArchitectureRules.MAPPERS_STAY_INSIDE_READ_AND_SANITIZED_PAYLOAD_BOUNDARIES.check(
                classes));
  }

  @Test
  void rejectsRequestToEntityMapper() {
    var classes =
        new ClassFileImporter()
            .importClasses(
                UnsafeMutationMapper.class,
                UnsafeMutationMapper.UnsafeRequest.class,
                UnsafeMutationMapper.UnsafeEntity.class);

    assertThrows(
        AssertionError.class,
        () -> ArchitectureRules.MAPPERS_DO_NOT_MUTATE_ENTITIES_FROM_COMMANDS.check(classes));
  }

  @Test
  void rejectsSecuritySecretsOutboxDomainTransitionsAndVersionMutation() {
    var classes =
        new ClassFileImporter()
            .importClasses(
                UnsafeBoundaryMappers.SecuritySecretMapper.class,
                UnsafeBoundaryMappers.OutboxMapper.class,
                UnsafeBoundaryMappers.DomainTransitionMapper.class,
                UnsafeBoundaryMappers.OptimisticVersionMutationMapper.class,
                UnsafeBoundaryMappers.PasswordHashSecret.class,
                UnsafeBoundaryMappers.OutboxEvent.class,
                UnsafeBoundaryMappers.WorkQueueAggregate.class,
                UnsafeBoundaryMappers.TransitionRequest.class,
                UnsafeBoundaryMappers.VersionCommand.class,
                UnsafeBoundaryMappers.SafeProjection.class,
                UnsafeBoundaryMappers.SafeDto.class);

    assertThrows(
        AssertionError.class,
        () -> ArchitectureRules.MAPPERS_STAY_INSIDE_READ_AND_SANITIZED_PAYLOAD_BOUNDARIES.check(classes));
  }
}
