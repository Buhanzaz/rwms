package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.datatype.EnumClass;

public enum RentalAttributeDataType implements EnumClass<String> {
    STRING("STRING"),
    TEXT("TEXT"),
    NUMBER("NUMBER"),
    BOOLEAN("BOOLEAN"),
    ENUM("ENUM");

    private final String id;

    RentalAttributeDataType(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    public static RentalAttributeDataType fromId(String id) {
        for (RentalAttributeDataType value : RentalAttributeDataType.values()) {
            if (value.getId().equals(id)) {
                return value;
            }
        }
        return null;
    }
}
