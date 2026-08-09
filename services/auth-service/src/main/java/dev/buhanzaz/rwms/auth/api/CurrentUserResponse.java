package dev.buhanzaz.rwms.auth.api;

import dev.buhanzaz.rwms.auth.domain.PrincipalType;
import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import java.util.List;
import java.util.UUID;

/**
 * Effective authorization projection for the interactive user represented by the current JWT.
 *
 * <p>{@code warehouseAccessAll} identifies roles with access to every warehouse, while
 * {@code warehouseAccesses} lists the effective per-warehouse grants for the user.
 *
 * @param id stable authorization-subject identifier
 * @param username canonical login name
 * @param displayName human-readable name derived from the profile
 * @param firstName optional given name
 * @param lastName optional family name
 * @param email optional email address
 * @param principalType identity kind for the current principal
 * @param globalRole current interactive-user role
 * @param rentalAccess persisted rental entitlement
 * @param warehouseAccessAll whether the role has unrestricted warehouse access
 * @param warehouseAccesses effective configured warehouse grants
 */
public record CurrentUserResponse(
        UUID id,
        String username,
        String displayName,
        String firstName,
        String lastName,
        String email,
        PrincipalType principalType,
        UserGlobalRole globalRole,
        boolean rentalAccess,
        boolean warehouseAccessAll,
        List<EffectiveWarehouseAccessDto> warehouseAccesses) {

    /**
     * Compatibility constructor for callers that do not include the persisted rental entitlement.
     *
     * <p>The absent entitlement is represented as {@code false}.
     *
     * @param id stable authorization-subject identifier
     * @param username canonical login name
     * @param displayName human-readable name derived from the profile
     * @param firstName optional given name
     * @param lastName optional family name
     * @param email optional email address
     * @param principalType identity kind for the current principal
     * @param globalRole current interactive-user role
     * @param warehouseAccessAll whether the role has unrestricted warehouse access
     * @param warehouseAccesses effective configured warehouse grants
     */
    public CurrentUserResponse(
            UUID id,
            String username,
            String displayName,
            String firstName,
            String lastName,
            String email,
            PrincipalType principalType,
            UserGlobalRole globalRole,
            boolean warehouseAccessAll,
            List<EffectiveWarehouseAccessDto> warehouseAccesses) {
        this(
                id,
                username,
                displayName,
                firstName,
                lastName,
                email,
                principalType,
                globalRole,
                false,
                warehouseAccessAll,
                warehouseAccesses);
    }
}
