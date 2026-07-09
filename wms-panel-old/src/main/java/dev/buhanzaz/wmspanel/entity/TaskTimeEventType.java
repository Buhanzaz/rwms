package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.datatype.EnumClass;

public enum TaskTimeEventType implements EnumClass<String> {
    STARTED("STARTED"),
    PAUSED("PAUSED"),
    RESUMED("RESUMED"),
    FINISHED("FINISHED");

    private final String id;

    TaskTimeEventType(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    public static TaskTimeEventType fromId(String id) {
        for (TaskTimeEventType value : TaskTimeEventType.values()) {
            if (value.getId().equals(id)) {
                return value;
            }
        }
        return null;
    }
}
