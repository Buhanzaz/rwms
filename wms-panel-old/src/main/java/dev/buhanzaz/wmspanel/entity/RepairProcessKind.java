package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.datatype.EnumClass;

public enum RepairProcessKind implements EnumClass<String> {
    ESTIMATE_REPAIR("ESTIMATE_REPAIR"),
    REWORK("REWORK");

    private final String id;

    RepairProcessKind(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    public static RepairProcessKind fromId(String id) {
        for (RepairProcessKind value : values()) {
            if (value.id.equals(id)) {
                return value;
            }
        }
        return null;
    }
}
