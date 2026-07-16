package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.datatype.EnumClass;

public enum RepairEstimateLineType implements EnumClass<String> {
    WORK("WORK"),
    MATERIAL("MATERIAL");

    private final String id;

    RepairEstimateLineType(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    public static RepairEstimateLineType fromId(String id) {
        for (RepairEstimateLineType value : RepairEstimateLineType.values()) {
            if (value.getId().equals(id)) {
                return value;
            }
        }
        return null;
    }
}
