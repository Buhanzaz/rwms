package dev.buhanzaz.rwms.auth.domain;

/** Global role assigned to an interactive RWMS user. */
public enum UserGlobalRole {
    /** Administrator with system-wide authority. */
    SYSTEM_ADMIN,
    /** Administrator of warehouse-management operations. */
    WMS_ADMIN,
    /** Manager responsible for warehouse operations. */
    WAREHOUSE_MANAGER,
    /** Manager responsible for rental operations. */
    RENTAL_MANAGER,
    /** Customer using only the dedicated rental application boundary. */
    CUSTOMER,
    /** Read-only interactive user. */
    VIEWER;

    /**
     * Determines whether this role may be granted access to the manager mobile application.
     *
     * @return {@code true} for roles allowed to use the manager application
     */
    public boolean isManagerAppEligible() {
        return this == SYSTEM_ADMIN || this == WMS_ADMIN || this == WAREHOUSE_MANAGER;
    }

    /**
     * Determines the default rental-access entitlement for a newly created user of this role.
     *
     * @return {@code true} when the role receives rental access by default
     */
    public boolean hasRentalAccessByDefault() {
        return this == SYSTEM_ADMIN || this == WMS_ADMIN || this == RENTAL_MANAGER;
    }
}
