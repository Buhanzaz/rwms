package dev.buhanzaz.rwms.assistant.domain;

/** Review and replay state for sanitized assistant booking-event dead-letter evidence. */
public enum AssistantEventReplayState {
  NOT_REPLAYABLE,
  AWAITING_REVIEW,
  APPROVED,
  REJECTED,
  REPLAYED
}
