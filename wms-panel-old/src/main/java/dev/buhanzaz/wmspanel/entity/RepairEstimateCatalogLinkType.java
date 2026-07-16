package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.datatype.EnumClass;

public enum RepairEstimateCatalogLinkType implements EnumClass<String> {
    DEPENDENCY("DEPENDENCY"),
    FOLLOW_UP("FOLLOW_UP");

    private final String id;

    RepairEstimateCatalogLinkType(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    public static RepairEstimateCatalogLinkType fromId(String id) {
        for (RepairEstimateCatalogLinkType value : RepairEstimateCatalogLinkType.values()) {
            if (value.getId().equals(id)) {
                return value;
            }
        }
        return null;
    }
}
