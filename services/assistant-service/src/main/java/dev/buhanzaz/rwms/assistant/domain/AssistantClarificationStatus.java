package dev.buhanzaz.rwms.assistant.domain;

/** Describes whether an interactive question can still accept a server-validated option. */
public enum AssistantClarificationStatus {
  PENDING,
  ANSWERED,
  SUPERSEDED
}
