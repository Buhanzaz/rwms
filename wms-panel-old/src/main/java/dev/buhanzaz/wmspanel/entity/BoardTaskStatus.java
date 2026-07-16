package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.datatype.EnumClass;

public enum BoardTaskStatus implements EnumClass<String> {
    ACTIVE("ACTIVE"),
    DONE("DONE"),
    CANCELLED("CANCELLED");

    private final String id;

    BoardTaskStatus(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    public static BoardTaskStatus fromId(String id) {
        for (BoardTaskStatus value : BoardTaskStatus.values()) {
            if (value.getId().equals(id)) {
                return value;
            }
        }
        return null;
    }
}
