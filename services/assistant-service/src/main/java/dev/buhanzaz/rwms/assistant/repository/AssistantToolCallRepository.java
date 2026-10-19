package dev.buhanzaz.rwms.assistant.repository;

import dev.buhanzaz.rwms.assistant.domain.AssistantToolCall;
import dev.buhanzaz.rwms.assistant.domain.AssistantToolCallStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores tool-call history and exposes payload-free lifecycle observations for recovery monitoring.
 *
 * <p>Conversation reads remain deterministically ordered. Recovery queries select only counts and
 * timestamps, so a metrics scrape does not hydrate provider arguments, results, or failure data.
 */
public interface AssistantToolCallRepository extends JpaRepository<AssistantToolCall, UUID> {
  List<AssistantToolCall> findByConversationIdOrderByCreatedAtAscIdAsc(UUID conversationId);

  Optional<AssistantToolCall>
      findFirstByConversationIdAndToolNameAndStatusOrderByCompletedAtDescIdDesc(
          UUID conversationId, String toolName, AssistantToolCallStatus status);

  /**
   * Counts durable tool calls in one exact lifecycle status without reading their payloads.
   *
   * @param status lifecycle status to count
   * @return number of matching durable tool calls
   */
  @Transactional(readOnly = true)
  long countByStatus(AssistantToolCallStatus status);

  /**
   * Finds the earliest creation timestamp in one lifecycle status without hydrating an entity.
   *
   * @param status lifecycle status to observe
   * @return earliest matching timestamp, or empty when no row has that status
   */
  @Transactional(readOnly = true)
  @Query(
      """
      select min(toolCall.createdAt)
      from AssistantToolCall toolCall
      where toolCall.status = :status
      """)
  Optional<OffsetDateTime> findOldestCreatedAtByStatus(
      @Param("status") AssistantToolCallStatus status);
}
