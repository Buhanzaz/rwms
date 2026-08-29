package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferPlanView;

/** Immutable result and replay marker for transfer planning commands. */
record TransferPlanCommandResult(TransferPlanView response, boolean replayed) {}
