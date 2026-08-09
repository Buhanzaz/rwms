package dev.buhanzaz.rwms.assistant.domain;

/** Defines the durable lifecycle of a persisted tool invocation so completed results can be replayed safely. */
public enum AssistantToolCallStatus {
  STARTED,
  COMPLETED,
  FAILED
}
