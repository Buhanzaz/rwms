package dev.buhanzaz.rwms.warehouse.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Creates one warehouse in the installation-wide directory.
 *
 * <p>A regular object must be production, a primary RWMS warehouse, or both. A representative
 * object has neither flag and names exactly one responsible parent.
 */
public record CreateWarehouseRequest(
    @NotBlank @Size(max = 255) String name,
    @NotBlank @Size(max = 255) String city,
    @Size(max = 1000) String address,
    @DecimalMin("-90.000000") @DecimalMax("90.000000") @Digits(integer = 2, fraction = 6)
        BigDecimal latitude,
    @DecimalMin("-180.000000") @DecimalMax("180.000000") @Digits(integer = 3, fraction = 6)
        BigDecimal longitude,
    @NotBlank @Size(max = 64) String timeZone,
    @Min(0) Integer sortOrder,
    boolean production,
    boolean mainWarehouse,
    UUID representativeParentWarehouseId) {}
