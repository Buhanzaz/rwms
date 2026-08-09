package dev.buhanzaz.rwms.auth.api;

import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import java.util.List;
import java.util.UUID;

/**
 * Administrative projection of an interactive user and all of its configured warehouse grants.
 *
 * <p>{@code version} is the aggregate version used to fence mutable commands. Rental access is a
 * persisted entitlement rather than a value callers should infer from the global role.
 *
 * @param id stable authorization-subject identifier
 * @param version current aggregate version for optimistic concurrency
 * @param username canonical login name
 * @param firstName optional given name
 * @param lastName optional family name
 * @param email optional email address
 * @param timeZoneId optional IANA time-zone identifier
 * @param active whether the user may authenticate
 * @param globalRole user's global RWMS role
 * @param mobileAppAccess whether the user may use the manager mobile application
 * @param rentalAccess persisted rental entitlement
 * @param warehouseAccesses complete set of configured warehouse grants
 */
public record AdminUserResponse(
        UUID id,
        int version,
        String username,
        String firstName,
        String lastName,
        String email,
        String timeZoneId,
        boolean active,
        UserGlobalRole globalRole,
        boolean mobileAppAccess,
        boolean rentalAccess,
        List<WarehouseAccessDto> warehouseAccesses) {

    /**
     * Compatibility constructor for callers that do not provide a rental-access value.
     *
     * <p>It represents the absent entitlement as {@code false}.
     *
     * @param id stable authorization-subject identifier
     * @param version current aggregate version
     * @param username canonical login name
     * @param firstName optional given name
     * @param lastName optional family name
     * @param email optional email address
     * @param timeZoneId optional IANA time-zone identifier
     * @param active whether the user may authenticate
     * @param globalRole user's global RWMS role
     * @param mobileAppAccess manager-mobile entitlement
     * @param warehouseAccesses complete configured warehouse grants
     */
    public AdminUserResponse(
            UUID id,
            int version,
            String username,
            String firstName,
            String lastName,
            String email,
            String timeZoneId,
            boolean active,
            UserGlobalRole globalRole,
            boolean mobileAppAccess,
            List<WarehouseAccessDto> warehouseAccesses) {
        this(
                id,
                version,
                username,
                firstName,
                lastName,
                email,
                timeZoneId,
                active,
                globalRole,
                mobileAppAccess,
                false,
                warehouseAccesses);
    }

    /**
     * Compatibility constructor for callers that do not provide mobile or rental entitlements.
     *
     * <p>Both absent entitlements are represented as {@code false}.
     *
     * @param id stable authorization-subject identifier
     * @param version current aggregate version
     * @param username canonical login name
     * @param firstName optional given name
     * @param lastName optional family name
     * @param email optional email address
     * @param timeZoneId optional IANA time-zone identifier
     * @param active whether the user may authenticate
     * @param globalRole user's global RWMS role
     * @param warehouseAccesses complete configured warehouse grants
     */
    public AdminUserResponse(
            UUID id,
            int version,
            String username,
            String firstName,
            String lastName,
            String email,
            String timeZoneId,
            boolean active,
            UserGlobalRole globalRole,
            List<WarehouseAccessDto> warehouseAccesses) {
        this(
                id,
                version,
                username,
                firstName,
                lastName,
                email,
                timeZoneId,
                active,
                globalRole,
                false,
                false,
                warehouseAccesses);
    }
}
