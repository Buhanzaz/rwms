package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.datatype.EnumClass;

public enum WarehouseAccessLevel implements EnumClass<String> {
    VIEW("VIEW"),
    EDIT("EDIT"),
    MANAGE("MANAGE");

    private final String id;

    WarehouseAccessLevel(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    public static WarehouseAccessLevel fromId(String id) {
        for (WarehouseAccessLevel value : WarehouseAccessLevel.values()) {
            if (value.getId().equals(id)) {
                return value;
            }
        }
        return null;
    }
}