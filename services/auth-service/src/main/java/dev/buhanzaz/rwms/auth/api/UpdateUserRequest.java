package dev.buhanzaz.rwms.auth.api;

import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Optimistically fenced command for replacing an interactive user's profile and authorization data.
 *
 * <p>An omitted {@code mobileAppAccess} retains the current entitlement when it remains role
 * eligible. An omitted {@code rentalAccess} retains the persisted rental entitlement.
 *
 * @param expectedVersion current aggregate version expected by the administrator
 * @param username replacement canonical login name
 * @param firstName replacement optional given name
 * @param lastName replacement optional family name
 * @param email replacement optional email address
 * @param timeZoneId replacement optional IANA time-zone identifier
 * @param active replacement authentication state
 * @param globalRole replacement global role
 * @param mobileAppAccess optional replacement manager-mobile entitlement
 * @param rentalAccess optional replacement rental entitlement
 */
public record UpdateUserRequest(
        @NotNull Integer expectedVersion,
        @NotBlank @Size(max = 128) String username,
        @Size(max = 128) String firstName,
        @Size(max = 128) String lastName,
        @Email @Size(max = 255) String email,
        @Size(max = 64) String timeZoneId,
        @NotNull Boolean active,
        @NotNull UserGlobalRole globalRole,
        Boolean mobileAppAccess,
        Boolean rentalAccess) {

    /**
     * Compatibility constructor that leaves both optional application entitlements unspecified.
     *
     * @param expectedVersion current aggregate version
     * @param username replacement canonical login name
     * @param firstName replacement optional given name
     * @param lastName replacement optional family name
     * @param email replacement optional email address
     * @param timeZoneId replacement optional IANA time-zone identifier
     * @param active replacement authentication state
     * @param globalRole replacement global role
     */
    public UpdateUserRequest(
            Integer expectedVersion,
            String username,
            String firstName,
            String lastName,
            String email,
            String timeZoneId,
            Boolean active,
            UserGlobalRole globalRole) {
        this(
                expectedVersion,
                username,
                firstName,
                lastName,
                email,
                timeZoneId,
                active,
                globalRole,
                null,
                null);
    }

    /**
     * Compatibility constructor that supplies mobile access while leaving rental access unspecified.
     *
     * @param expectedVersion current aggregate version
     * @param username replacement canonical login name
     * @param firstName replacement optional given name
     * @param lastName replacement optional family name
     * @param email replacement optional email address
     * @param timeZoneId replacement optional IANA time-zone identifier
     * @param active replacement authentication state
     * @param globalRole replacement global role
     * @param mobileAppAccess optional replacement manager-mobile entitlement
     */
    public UpdateUserRequest(
            Integer expectedVersion,
            String username,
            String firstName,
            String lastName,
            String email,
            String timeZoneId,
            Boolean active,
            UserGlobalRole globalRole,
            Boolean mobileAppAccess) {
        this(
                expectedVersion,
                username,
                firstName,
                lastName,
                email,
                timeZoneId,
                active,
                globalRole,
                mobileAppAccess,
                null);
    }
}
