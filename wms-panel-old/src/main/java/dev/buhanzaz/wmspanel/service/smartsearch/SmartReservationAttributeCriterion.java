package dev.buhanzaz.wmspanel.service.smartsearch;

import dev.buhanzaz.wmspanel.entity.RentalAttributeDefinition;
import dev.buhanzaz.wmspanel.entity.RentalAttributeOption;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record SmartReservationAttributeCriterion(
        String key,
        RentalAttributeDefinition definition,
        List<RentalAttributeOption> options,
        Double numberValue,
        Boolean booleanValue,
        String textValue) {

    public SmartReservationAttributeCriterion {
        options = options == null ? List.of() : List.copyOf(options);
        textValue = normalize(textValue);
    }

    public String label() {
        String attributeName = definition == null || definition.getName() == null || definition.getName().isBlank()
                ? "Атрибут"
                : definition.getName();
        if (!options.isEmpty()) {
            return attributeName + ": " + options.stream()
                    .map(option -> option == null || option.getName() == null ? "" : option.getName())
                    .filter(value -> !value.isBlank())
                    .reduce((left, right) -> left + " / " + right)
                    .orElse("");
        }
        if (numberValue != null) {
            return attributeName + ": " + (Math.rint(numberValue) == numberValue ? Long.toString(Math.round(numberValue)) : numberValue);
        }
        if (booleanValue != null) {
            return attributeName + ": " + (Boolean.TRUE.equals(booleanValue) ? "да" : "нет");
        }
        if (textValue != null && !textValue.isBlank()) {
            return attributeName + ": " + textValue;
        }
        return attributeName;
    }

    public boolean isEmpty() {
        return options.isEmpty() && numberValue == null && booleanValue == null && (textValue == null || textValue.isBlank());
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    public boolean sameDefinition(RentalAttributeDefinition otherDefinition) {
        if (definition == null || otherDefinition == null || definition.getId() == null || otherDefinition.getId() == null) {
            return false;
        }
        return Objects.equals(definition.getId(), otherDefinition.getId());
    }
}
