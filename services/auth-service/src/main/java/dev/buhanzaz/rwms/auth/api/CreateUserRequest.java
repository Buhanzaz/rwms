package dev.buhanzaz.rwms.auth.api;

import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Command for creating an interactive user with optional application entitlements and warehouse
 * grants.
 *
 * <p>An omitted {@code active} value defaults to enabled. An omitted {@code rentalAccess} value
 * is derived from the requested global role by the owning administration service.
 *
 * @param username requested canonical login name
 * @param password write-only initial plaintext password
 * @param firstName optional given name
 * @param lastName optional family name
 * @param email optional email address
 * @param timeZoneId optional IANA time-zone identifier
 * @param globalRole requested global role
 * @param active optional initial authentication state
 * @param mobileAppAccess optional manager-mobile entitlement
 * @param rentalAccess optional persisted rental entitlement
 * @param warehouseAccesses optional initial warehouse grants
 */
public record CreateUserRequest(
        @NotBlank @Size(max = 128) String username,
        @NotBlank @Size(min = 8, max = 200) String password,
        @Size(max = 128) String firstName,
        @Size(max = 128) String lastName,
        @Email @Size(max = 255) String email,
        @Size(max = 64) String timeZoneId,
        @NotNull UserGlobalRole globalRole,
        Boolean active,
        Boolean mobileAppAccess,
        Boolean rentalAccess,
        List<@Valid WarehouseAccessRequest> warehouseAccesses) {

    /**
     * Compatibility constructor that leaves both optional application entitlements unspecified.
     *
     * @param username requested canonical login name
     * @param password write-only initial plaintext password
     * @param firstName optional given name
     * @param lastName optional family name
     * @param email optional email address
     * @param timeZoneId optional IANA time-zone identifier
     * @param globalRole requested global role
     * @param active optional initial authentication state
     * @param warehouseAccesses optional initial warehouse grants
     */
    public CreateUserRequest(
            String username,
            String password,
            String firstName,
            String lastName,
            String email,
            String timeZoneId,
            UserGlobalRole globalRole,
            Boolean active,
            List<WarehouseAccessRequest> warehouseAccesses) {
        this(
                username,
                password,
                firstName,
                lastName,
                email,
                timeZoneId,
                globalRole,
                active,
                null,
                null,
                warehouseAccesses);
    }

    /**
     * Compatibility constructor that supplies mobile access while leaving rental access unspecified.
     *
     * @param username requested canonical login name
     * @param password write-only initial plaintext password
     * @param firstName optional given name
     * @param lastName optional family name
     * @param email optional email address
     * @param timeZoneId optional IANA time-zone identifier
     * @param globalRole requested global role
     * @param active optional initial authentication state
     * @param mobileAppAccess optional manager-mobile entitlement
     * @param warehouseAccesses optional initial warehouse grants
     */
    public CreateUserRequest(
            String username,
            String password,
            String firstName,
            String lastName,
            String email,
            String timeZoneId,
            UserGlobalRole globalRole,
            Boolean active,
            Boolean mobileAppAccess,
            List<WarehouseAccessRequest> warehouseAccesses) {
        this(
                username,
                password,
                firstName,
                lastName,
                email,
                timeZoneId,
                globalRole,
                active,
                mobileAppAccess,
                null,
                warehouseAccesses);
    }
}
