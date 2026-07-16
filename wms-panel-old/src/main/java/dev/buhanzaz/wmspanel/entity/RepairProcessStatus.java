package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.datatype.EnumClass;

public enum RepairProcessStatus implements EnumClass<String> {
    ACTIVE("ACTIVE"),
    AFTER_REPAIR("AFTER_REPAIR"),
    REWORK("REWORK"),
    ACCEPTED("ACCEPTED"),
    CANCELLED("CANCELLED");

    private final String id;

    RepairProcessStatus(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    public static RepairProcessStatus fromId(String id) {
        for (RepairProcessStatus value : values()) {
            if (value.id.equals(id)) {
                return value;
            }
        }
        return null;
    }
}
