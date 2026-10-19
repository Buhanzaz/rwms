package dev.buhanzaz.rwms.maintenance.disposition.domain;

import java.util.UUID;

/** Immutable client-independent observation of one furniture balance in a cabin. */
public record PropertyDispositionContentSnapshotLineDraft(
    UUID equipmentId,
    String equipmentName,
    String equipmentFormat,
    long currentQuantity,
    long moveQuantity,
    long expectedBalanceVersion) {}
