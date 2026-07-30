package dev.buhanzaz.rwms.assistant.repository;

import dev.buhanzaz.rwms.assistant.domain.AssistantMessage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssistantMessageRepository extends JpaRepository<AssistantMessage, UUID> {
  List<AssistantMessage> findByConversationIdOrderByCreatedAtAscIdAsc(UUID conversationId);
}
