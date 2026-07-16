package dev.buhanzaz.rwms.auth.domain;

public enum WarehouseAccessLevel {
    VIEW,
    EDIT,
    MANAGE;

    public WarehouseAccessLevel effectiveFor(UserGlobalRole role) {
        return role == UserGlobalRole.VIEWER ? VIEW : this;
    }
}
