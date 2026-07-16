package dev.buhanzaz.rwms.auth.api;

import jakarta.validation.constraints.Min;

public record RequeueOutboxRequest(@Min(1) int expectedAttemptCount) {}
