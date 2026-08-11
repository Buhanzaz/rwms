package dev.buhanzaz.rwms.assistant.service;

import dev.buhanzaz.rwms.assistant.api.AssistantApiModels;
import dev.buhanzaz.rwms.assistant.domain.AssistantConversation;
import dev.buhanzaz.rwms.assistant.integration.LogisticsClient;
import dev.buhanzaz.rwms.assistant.repository.AssistantConversationRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the short local transactions used before and after an idempotent remote inquiry creation. No
 * logistics call is made while this store holds a database transaction or advisory lock.
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

  /** Returns the one active owner-scoped conversation already linked to an order. */
  @Transactional(readOnly = true)
  public AssistantConversation activeForOrder(UUID rentalOrderId, UUID ownerSubjectId) {
    if (rentalOrderId == null) return null;
    AssistantConversation existing =
        conversations.findByRentalOrderIdAndArchivedFalse(rentalOrderId).orElse(null);
    if (existing != null) requireOwner(existing, ownerSubjectId);
    return existing;
  }

  /**
   * Archives one exact terminal inquiry under the existing order lock. If another caller already
   * replaced it with a fresh active conversation, that winner is returned without being changed.
   */
  @Transactional
  public AssistantConversation archiveTerminalForOrder(
      UUID ownerSubjectId,
      UUID rentalOrderId,
      UUID clientId,
      UUID expectedConversationId,
      UUID expectedRentalInquiryId) {
    conversations.acquireTransactionLock("assistant-conversation:rental-order:" + rentalOrderId);
    AssistantConversation current =
        conversations.findByRentalOrderIdAndArchivedFalse(rentalOrderId).orElse(null);
    if (current == null) return null;
    requireOwner(current, ownerSubjectId);
    requireImmutableLinks(current, clientId, rentalOrderId);
    if (!current.getId().equals(expectedConversationId)) return current;
    if (!current.getRentalInquiryId().equals(expectedRentalInquiryId)) {
      throw new AssistantConflictException("Conversation inquiry is immutable");
    }
    if (current.archive()) conversations.saveAndFlush(current);
    return null;
  }

  /**
   * Under one short advisory-lock transaction, creates the local link or validates the row won by a
   * concurrent caller using the same remote idempotency key.
   */
  @Transactional
  public AssistantConversation createOrValidate(
      UUID conversationId,
      UUID ownerSubjectId,
      LogisticsClient.InquiryBootstrap inquiry,
      UUID requestedClientId,
      AssistantApiModels.NewClientRequest newClient) {
    return createOrValidate(
        conversationId, ownerSubjectId, inquiry, requestedClientId, newClient, null);
  }

  /**
   * Creates or validates immutable links, serializing by conversation ID or by the optional order
   * ID so different concurrent create keys still resolve to one active order conversation.
   */
  @Transactional
  public AssistantConversation createOrValidate(
      UUID conversationId,
      UUID ownerSubjectId,
      LogisticsClient.InquiryBootstrap inquiry,
      UUID requestedClientId,
      AssistantApiModels.NewClientRequest newClient,
      UUID requestedRentalOrderId) {
    String lockIdentity =
        requestedRentalOrderId == null
            ? "conversation:" + conversationId
            : "rental-order:" + requestedRentalOrderId;
    conversations.acquireTransactionLock("assistant-conversation:" + lockIdentity);
    AssistantConversation existingForOrder =
        requestedRentalOrderId == null
            ? null
            : conversations
                .findByRentalOrderIdAndArchivedFalse(requestedRentalOrderId)
                .orElse(null);
    if (existingForOrder != null) {
      requireOwner(existingForOrder, ownerSubjectId);
      requireImmutableLinks(existingForOrder, requestedClientId, requestedRentalOrderId);
      return existingForOrder;
    }
    AssistantConversation existing = conversations.findById(conversationId).orElse(null);
    if (existing != null) {
      requireOwner(existing, ownerSubjectId);
      if (!existing.getRentalInquiryId().equals(inquiry.rentalInquiryId())
          || !existing.getClientId().equals(inquiry.clientId())) {
        throw new AssistantConflictException("Conversation client is immutable");
      }
      requireImmutableLinks(existing, requestedClientId, requestedRentalOrderId);
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
            requestedRentalOrderId,
            firstNonBlank(inquiry.clientType(), newClient == null ? null : newClient.clientType()),
            inquiry.clientDisplayName());
    return conversations.saveAndFlush(created);
  }

  private static void requireImmutableLinks(
      AssistantConversation conversation, UUID clientId, UUID rentalOrderId) {
    if ((clientId != null && !conversation.getClientId().equals(clientId))
        || !java.util.Objects.equals(conversation.getRentalOrderId(), rentalOrderId)) {
      throw new AssistantConflictException("Conversation client and rental order are immutable");
    }
  }

  private static void requireOwner(AssistantConversation conversation, UUID ownerSubjectId) {
    if (!conversation.getOwnerSubjectId().equals(ownerSubjectId)) {
      throw new AssistantNotFoundException("Conversation was not found");
    }
  }

  private static String firstNonBlank(String preferred, String fallback) {
    return preferred != null && !preferred.isBlank() ? preferred : fallback;
  }
}
