package dev.buhanzaz.rwms.assistant.repository;

import dev.buhanzaz.rwms.assistant.domain.AssistantClarificationQuestion;
import dev.buhanzaz.rwms.assistant.domain.AssistantClarificationStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persists owner-checked interactive question state inside assistant-service. */
public interface AssistantClarificationQuestionRepository
    extends JpaRepository<AssistantClarificationQuestion, UUID> {
  List<AssistantClarificationQuestion> findByConversationIdOrderBySequenceNumberAsc(
      UUID conversationId);

  Optional<AssistantClarificationQuestion> findFirstByConversationIdOrderBySequenceNumberDesc(
      UUID conversationId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  List<AssistantClarificationQuestion> findByConversationIdAndStatusInOrderBySequenceNumberAsc(
      UUID conversationId, List<AssistantClarificationStatus> statuses);

  /** Serializes creation and answer transitions for one conversation queue. */
  @Query(
      value = "select 1 from pg_advisory_xact_lock(hashtextextended(cast(:lockKey as text), 0))",
      nativeQuery = true)
  Integer acquireTransactionLock(@Param("lockKey") String lockKey);
}
