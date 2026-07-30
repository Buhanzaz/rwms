package dev.buhanzaz.rwms.auth.domain;

public enum UserGlobalRole {
    SYSTEM_ADMIN,
    WMS_ADMIN,
    WAREHOUSE_MANAGER,
    RENTAL_MANAGER,
    VIEWER;

    public boolean isManagerAppEligible() {
        return this == SYSTEM_ADMIN || this == WMS_ADMIN || this == WAREHOUSE_MANAGER;
    }

    public boolean hasRentalAccessByDefault() {
        return this == SYSTEM_ADMIN || this == WMS_ADMIN || this == RENTAL_MANAGER;
    }
}
