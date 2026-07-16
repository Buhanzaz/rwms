package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.datatype.EnumClass;

public enum RepairEstimateTaskPlanGenerationStatus implements EnumClass<String> {
    PENDING_GENERATION("PENDING_GENERATION"),
    GENERATED("GENERATED"),
    FAILED("FAILED");

    private final String id;

    RepairEstimateTaskPlanGenerationStatus(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    public static RepairEstimateTaskPlanGenerationStatus fromId(String id) {
        for (RepairEstimateTaskPlanGenerationStatus value : RepairEstimateTaskPlanGenerationStatus.values()) {
            if (value.getId().equals(id)) {
                return value;
            }
        }
        return null;
    }
}
