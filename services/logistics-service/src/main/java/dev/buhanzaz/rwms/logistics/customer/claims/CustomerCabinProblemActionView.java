package dev.buhanzaz.rwms.logistics.customer.claims;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Read-only lifecycle history item exposed by the claims service foundation. */
public record CustomerCabinProblemActionView(
    UUID actionId,
    CustomerCabinProblemActionKind actionKind,
    CustomerCabinProblemStatus previousStatus,
    CustomerCabinProblemStatus lifecycleStatus,
    CustomerCabinProblemResolutionKind resolutionKind,
    UUID actorSubjectId,
    String commentText,
    OffsetDateTime occurredAt) {}
