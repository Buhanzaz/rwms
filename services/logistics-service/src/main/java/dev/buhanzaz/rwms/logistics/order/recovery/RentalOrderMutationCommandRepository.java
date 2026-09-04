package dev.buhanzaz.rwms.logistics.order.recovery;

import dev.buhanzaz.rwms.logistics.order.domain.recovery.RentalOrderMutationCommand;
import dev.buhanzaz.rwms.logistics.order.domain.recovery.RentalOrderMutationCommand.Operation;
import dev.buhanzaz.rwms.logistics.order.domain.recovery.RentalOrderMutationCommand.State;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persists and lease-locks logistics-owned rental-order mutation recovery commands. */
public interface RentalOrderMutationCommandRepository
    extends JpaRepository<RentalOrderMutationCommand, UUID> {
  Optional<RentalOrderMutationCommand>
      findByActorSubjectIdAndOperationAndIdempotencyKey(
          UUID actorSubjectId, Operation operation, UUID idempotencyKey);

  boolean existsByOrder_IdAndStateIn(UUID orderId, Collection<State> states);

  long countByState(State state);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select command from RentalOrderMutationCommand command where command.id = :id")
  Optional<RentalOrderMutationCommand> findForUpdate(@Param("id") UUID id);

  /** Locks a bounded due page while concurrent service instances skip already claimed rows. */
  @Query(
      value =
          """
          select command.*
          from rental_order_mutation_command command
          where command.state = 'PENDING'
            and command.next_attempt_at <= :timestamp
            and (command.lease_until is null or command.lease_until <= :timestamp)
          order by command.next_attempt_at, command.created_at, command.id
          for update skip locked
          limit :batchSize
          """,
      nativeQuery = true)
  List<RentalOrderMutationCommand> findDueForUpdate(
      @Param("timestamp") OffsetDateTime timestamp, @Param("batchSize") int batchSize);

  /** Returns PostgreSQL wall-clock time for portable multi-instance lease decisions. */
  @Query(value = "select clock_timestamp()", nativeQuery = true)
  Instant currentDatabaseTimestamp();
}
