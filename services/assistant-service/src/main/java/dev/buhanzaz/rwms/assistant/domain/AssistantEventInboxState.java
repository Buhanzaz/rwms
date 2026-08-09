package dev.buhanzaz.rwms.assistant.domain;

/** Durable processing state for one validated assistant booking-event receipt. */
public enum AssistantEventInboxState {
  LEGACY_PROCESSED,
  STAGED,
  PROCESSED,
  DLT
}
