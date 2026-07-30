package dev.buhanzaz.rwms.assistant.repository;

import dev.buhanzaz.rwms.assistant.domain.AssistantToolCall;
import dev.buhanzaz.rwms.assistant.domain.AssistantToolCallStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssistantToolCallRepository extends JpaRepository<AssistantToolCall, UUID> {
  List<AssistantToolCall> findByConversationIdOrderByCreatedAtAscIdAsc(UUID conversationId);

  Optional<AssistantToolCall>
      findFirstByConversationIdAndToolNameAndStatusOrderByCompletedAtDescIdDesc(
          UUID conversationId, String toolName, AssistantToolCallStatus status);
}
