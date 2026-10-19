package dev.buhanzaz.rwms.asset.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** Complete replacement fenced by the last version read, never a warehouse-local override. */
public record ReplaceCabinStatusColorsRequest(
    @NotNull @Min(0) Long expectedVersion,
    @NotNull @Size(min = 16, max = 16)
        Map<String, @NotNull @Pattern(regexp = "^#[0-9a-fA-F]{6}$") String> colors) {}
