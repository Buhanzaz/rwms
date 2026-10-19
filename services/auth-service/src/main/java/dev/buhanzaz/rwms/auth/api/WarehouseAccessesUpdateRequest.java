package dev.buhanzaz.rwms.auth.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Optimistically fenced command that replaces, rather than patches, a user's warehouse grants.
 *
 * @param expectedVersion current aggregate version expected by the administrator
 * @param accesses complete replacement set of requested warehouse grants
 */
public record WarehouseAccessesUpdateRequest(
        @NotNull Integer expectedVersion,
        @NotNull List<@Valid WarehouseAccessRequest> accesses) {
}
