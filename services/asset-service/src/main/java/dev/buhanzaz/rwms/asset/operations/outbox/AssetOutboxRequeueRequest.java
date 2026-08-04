package dev.buhanzaz.rwms.asset.operations.outbox;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** The review version fences a privileged terminal-outbox recovery decision. */
public record AssetOutboxRequeueRequest(
    @NotNull @Min(0) Long expectedReviewVersion,
    @NotNull String reason) {}
