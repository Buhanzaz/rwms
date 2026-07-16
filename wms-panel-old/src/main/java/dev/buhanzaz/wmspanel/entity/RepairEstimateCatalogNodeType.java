package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.datatype.EnumClass;

public enum RepairEstimateCatalogNodeType implements EnumClass<String> {
    CATEGORY("CATEGORY"),
    SUBCATEGORY("SUBCATEGORY"),
    WORK("WORK"),
    MATERIAL("MATERIAL"),
    LOCATION("LOCATION"),
    OPTION("OPTION");

    private final String id;

    RepairEstimateCatalogNodeType(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    public static RepairEstimateCatalogNodeType fromId(String id) {
        for (RepairEstimateCatalogNodeType value : RepairEstimateCatalogNodeType.values()) {
            if (value.getId().equals(id)) {
                return value;
            }
        }
        return null;
    }
}
