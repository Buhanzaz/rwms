package dev.buhanzaz.rwms.assistant.service;

import dev.buhanzaz.rwms.assistant.api.AssistantApiModels;
import dev.buhanzaz.rwms.assistant.domain.AssistantConversation;
import dev.buhanzaz.rwms.assistant.integration.LogisticsClient;
import dev.buhanzaz.rwms.assistant.repository.AssistantConversationRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the short local transactions used before and after an idempotent remote inquiry creation.
 * No logistics call is made while this store holds a database transaction or advisory lock.
 */
@Service
public class AssistantConversationCreationStore {
  private final AssistantConversationRepository conversations;

  public AssistantConversationCreationStore(AssistantConversationRepository conversations) {
    this.conversations = conversations;
  }

  /** Returns an existing owner-scoped row for a local replay without holding its transaction. */
  @Transactional(readOnly = true)
  public AssistantConversation existing(UUID conversationId, UUID ownerSubjectId) {
    AssistantConversation existing = conversations.findById(conversationId).orElse(null);
    if (existing != null) requireOwner(existing, ownerSubjectId);
    return existing;
  }

  /**
   * Under one short advisory-lock transaction, creates the local link or validates the row won by
   * a concurrent caller using the same remote idempotency key.
   */
  @Transactional
  public AssistantConversation createOrValidate(
      UUID conversationId,
      UUID ownerSubjectId,
      LogisticsClient.InquiryBootstrap inquiry,
      UUID requestedClientId,
      AssistantApiModels.NewClientRequest newClient) {
    conversations.acquireTransactionLock("assistant-conversation:" + conversationId);
    AssistantConversation existing = conversations.findById(conversationId).orElse(null);
    if (existing != null) {
      requireOwner(existing, ownerSubjectId);
      if (!existing.getRentalInquiryId().equals(inquiry.rentalInquiryId())
          || !existing.getClientId().equals(inquiry.clientId())
          || (requestedClientId != null && !existing.getClientId().equals(requestedClientId))) {
        throw new AssistantConflictException("Conversation client is immutable");
      }
      return existing;
    }
    if (requestedClientId != null && !requestedClientId.equals(inquiry.clientId())) {
      throw new AssistantConflictException("Conversation client is immutable");
    }
    AssistantConversation created =
        AssistantConversation.create(
            conversationId,
            ownerSubjectId,
            inquiry.clientId(),
            inquiry.rentalInquiryId(),
            firstNonBlank(
                inquiry.clientType(), newClient == null ? null : newClient.clientType()),
            inquiry.clientDisplayName());
    return conversations.saveAndFlush(created);
  }

  private static void requireOwner(
      AssistantConversation conversation, UUID ownerSubjectId) {
    if (!conversation.getOwnerSubjectId().equals(ownerSubjectId)) {
      throw new AssistantNotFoundException("Conversation was not found");
    }
  }

  private static String firstNonBlank(String preferred, String fallback) {
    return preferred != null && !preferred.isBlank() ? preferred : fallback;
  }
}
