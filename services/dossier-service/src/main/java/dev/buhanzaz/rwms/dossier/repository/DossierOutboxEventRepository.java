package dev.buhanzaz.rwms.dossier.repository;

import dev.buhanzaz.rwms.dossier.domain.DossierOutboxEvent;
import dev.buhanzaz.rwms.dossier.domain.DossierOutboxState;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DossierOutboxEventRepository extends JpaRepository<DossierOutboxEvent, UUID> {
  Optional<DossierOutboxEvent> findBySourceEventIdAndCabinId(UUID sourceEventId, UUID cabinId);

  boolean existsByCabinIdAndAggregateVersionLessThanAndStatusNot(
      UUID cabinId, long aggregateVersion, DossierOutboxState status);

  List<DossierOutboxEvent> findAllByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAscEventIdAsc(
      Collection<DossierOutboxState> states, OffsetDateTime now, Pageable pageable);

  @Query(
      """
      select event
      from DossierOutboxEvent event
      where event.status in :states
        and event.nextAttemptAt <= :now
        and not exists (
          select predecessor.eventId
          from DossierOutboxEvent predecessor
          where predecessor.cabinId = event.cabinId
            and predecessor.aggregateVersion < event.aggregateVersion
            and predecessor.status <> :published
        )
      order by event.createdAt asc, event.eventId asc
      """)
  List<DossierOutboxEvent> findPublishableHeads(
      @Param("states") Collection<DossierOutboxState> states,
      @Param("published") DossierOutboxState published,
      @Param("now") OffsetDateTime now,
      Pageable pageable);
}
