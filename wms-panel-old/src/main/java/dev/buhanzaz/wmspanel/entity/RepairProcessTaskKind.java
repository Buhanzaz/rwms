package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.datatype.EnumClass;

public enum RepairProcessTaskKind implements EnumClass<String> {
    REPAIR_WORK("REPAIR_WORK"),
    REWORK("REWORK"),
    MOVE_TO_REPAIR("MOVE_TO_REPAIR"),
    MOVE_FROM_REPAIR("MOVE_FROM_REPAIR");

    private final String id;

    RepairProcessTaskKind(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    public static RepairProcessTaskKind fromId(String id) {
        for (RepairProcessTaskKind value : values()) {
            if (value.id.equals(id)) {
                return value;
            }
        }
        return null;
    }
}
