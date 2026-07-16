package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.datatype.EnumClass;

public enum RepairEstimateStatus implements EnumClass<String> {
    DRAFT("DRAFT"),
    COMPLETED("COMPLETED");

    private final String id;

    RepairEstimateStatus(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    public static RepairEstimateStatus fromId(String id) {
        for (RepairEstimateStatus value : RepairEstimateStatus.values()) {
            if (value.getId().equals(id)) {
                return value;
            }
        }
        return null;
    }
}
