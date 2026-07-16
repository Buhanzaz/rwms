package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.datatype.EnumClass;

public enum TaskAssignmentStatus implements EnumClass<String> {
    ACTIVE("ACTIVE"),
    PAUSED("PAUSED"),
    DONE("DONE"),
    CANCELLED("CANCELLED");

    private final String id;

    TaskAssignmentStatus(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    public static TaskAssignmentStatus fromId(String id) {
        for (TaskAssignmentStatus value : TaskAssignmentStatus.values()) {
            if (value.getId().equals(id)) {
                return value;
            }
        }
        return null;
    }
}
