package dev.buhanzaz.rwms.auth.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record WarehouseAccessesUpdateRequest(
        @NotNull Integer expectedVersion,
        @NotNull List<@Valid WarehouseAccessRequest> accesses) {
}
