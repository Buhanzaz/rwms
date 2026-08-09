package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.LogisticsDocumentView;

/**
 * Internal command outcome shared by document workflow owners without making them depend on the
 * public facade's compatibility result type.
 */
record LogisticsDocumentCommandResult(LogisticsDocumentView response, boolean replayed) {}
