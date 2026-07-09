package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.datatype.EnumClass;

public enum UserGlobalRole implements EnumClass<String> {
    SYSTEM_ADMIN("SYSTEM_ADMIN"),
    WMS_ADMIN("WMS_ADMIN"),
    WAREHOUSE_MANAGER("WAREHOUSE_MANAGER"),
    RENTAL_MANAGER("RENTAL_MANAGER"),
    VIEWER("VIEWER");

    private final String id;

    UserGlobalRole(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    public static UserGlobalRole fromId(String id) {
        for (UserGlobalRole value : UserGlobalRole.values()) {
            if (value.getId().equals(id)) {
                return value;
            }
        }
        return null;
    }
}