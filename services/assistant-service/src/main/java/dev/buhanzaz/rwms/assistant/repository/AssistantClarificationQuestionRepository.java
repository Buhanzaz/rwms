package dev.buhanzaz.rwms.assistant.repository;

import dev.buhanzaz.rwms.assistant.domain.AssistantClarificationQuestion;
import dev.buhanzaz.rwms.assistant.domain.AssistantClarificationStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/** Persists owner-checked interactive question state inside assistant-service. */
public interface AssistantClarificationQuestionRepository
    extends JpaRepository<AssistantClarificationQuestion, UUID> {
  List<AssistantClarificationQuestion> findByConversationIdOrderByCreatedAtAscIdAsc(
      UUID conversationId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<AssistantClarificationQuestion> findByIdAndConversationId(
      UUID id, UUID conversationId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  List<AssistantClarificationQuestion>
      findByConversationIdAndBranchKeyAndStatusOrderByCreatedAtAscIdAsc(
          UUID conversationId, String branchKey, AssistantClarificationStatus status);
}
