package dev.buhanzaz.rwms.platform.contracts;

import java.util.List;
import java.util.Objects;

/** Framework-neutral zero-based page response. */
public record PageResponse<T>(List<T> items, long page, long size, long totalElements, long totalPages) {

    public PageResponse {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive");
        }
        if (totalElements < 0) {
            throw new IllegalArgumentException("totalElements must not be negative");
        }
        if (totalPages < 0) {
            throw new IllegalArgumentException("totalPages must not be negative");
        }
    }
}
