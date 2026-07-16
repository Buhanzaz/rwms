package dev.buhanzaz.wmspanel.service;

import dev.buhanzaz.wmspanel.entity.RentalAttributeValue;
import dev.buhanzaz.wmspanel.entity.RentalType;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
public class RentalTypeDisplayFormatter {

    public String displayName(RentalType type) {
        if (type == null) {
            return "";
        }
        return safe(type.getName());
    }

    public String displayName(RentalType type, Map<UUID, RentalAttributeValue> valuesByDefinition) {
        return displayName(type);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
