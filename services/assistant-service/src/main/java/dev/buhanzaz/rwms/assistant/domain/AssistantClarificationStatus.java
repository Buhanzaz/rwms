package dev.buhanzaz.rwms.assistant.domain;

/** Describes one durable state in the ordered clarification queue. */
public enum AssistantClarificationStatus {
  QUEUED,
  PENDING,
  ANSWERED,
  SUPERSEDED
}
