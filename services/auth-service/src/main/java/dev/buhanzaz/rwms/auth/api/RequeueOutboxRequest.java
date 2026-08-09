package dev.buhanzaz.rwms.auth.api;

import jakarta.validation.constraints.Min;

/**
 * Recovery command fenced by the outbox or dead-letter row's current attempt count.
 *
 * @param expectedAttemptCount current delivery-attempt count observed by the operator
 */
public record RequeueOutboxRequest(@Min(1) int expectedAttemptCount) {}
