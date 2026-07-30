package dev.buhanzaz.rwms.assistant.repository;

import dev.buhanzaz.rwms.assistant.domain.AssistantConversation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssistantConversationRepository extends JpaRepository<AssistantConversation, UUID> {
  List<AssistantConversation> findByOwnerSubjectIdOrderByUpdatedAtDesc(UUID ownerSubjectId);

  Optional<AssistantConversation> findByIdAndOwnerSubjectId(UUID id, UUID ownerSubjectId);

  Optional<AssistantConversation> findByIdAndOwnerSubjectIdAndArchivedFalse(
      UUID id, UUID ownerSubjectId);

  /** Serializes first-use idempotency before either local or remote creation. */
  @Query(
      value =
          "select 1 from pg_advisory_xact_lock(hashtextextended(cast(:lockKey as text), 0))",
      nativeQuery = true)
  Integer acquireTransactionLock(@Param("lockKey") String lockKey);
}
