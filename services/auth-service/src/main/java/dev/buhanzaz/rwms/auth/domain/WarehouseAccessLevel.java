package dev.buhanzaz.rwms.auth.domain;

/** Level of authority granted to a user for one warehouse. */
public enum WarehouseAccessLevel {
    /** Allows read-only access to the warehouse. */
    VIEW,
    /** Allows operational changes permitted to an editor. */
    EDIT,
    /** Allows warehouse-management actions. */
    MANAGE;

    /**
     * Applies role-wide restrictions to a configured warehouse grant.
     *
     * <p>Viewers always receive effective read-only access, regardless of the stored grant.
     *
     * @param role the user's global role
     * @return the level effective for that role
     */
    public WarehouseAccessLevel effectiveFor(UserGlobalRole role) {
        return role == UserGlobalRole.VIEWER ? VIEW : this;
    }
}
