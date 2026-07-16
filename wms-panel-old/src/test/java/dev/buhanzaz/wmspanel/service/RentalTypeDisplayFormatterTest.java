package dev.buhanzaz.wmspanel.service;

import dev.buhanzaz.wmspanel.entity.RentalAttributeDefinition;
import dev.buhanzaz.wmspanel.entity.RentalAttributeOption;
import dev.buhanzaz.wmspanel.entity.RentalAttributeValue;
import dev.buhanzaz.wmspanel.entity.RentalType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RentalTypeDisplayFormatterTest {

    private final RentalTypeDisplayFormatter formatter = new RentalTypeDisplayFormatter();

    @Test
    void returnsBaseTypeNameEvenWhenDynamicAttributesAreProvided() {
        RentalType type = new RentalType();
        type.setName("Санблок");

        Map<UUID, RentalAttributeValue> values = new LinkedHashMap<>();
        values.put(UUID.randomUUID(), numberValue("SHOWERS", 2));
        values.put(UUID.randomUUID(), numberValue("TOILETS", 2));
        values.put(UUID.randomUUID(), numberValue("SINKS", 2));
        values.put(UUID.randomUUID(), optionValue("BOILER", "200L", "200 л"));

        assertThat(formatter.displayName(type, values))
                .isEqualTo("Санблок");
    }

    @Test
    void fallsBackToBaseTypeNameWhenTemplateIsMissing() {
        RentalType type = new RentalType();
        type.setName("R2");

        assertThat(formatter.displayName(type, Map.of()))
                .isEqualTo("R2");
    }

    @Test
    void ignoresAttributeMapWhenCurrentFormatterHasNoTemplateLogic() {
        RentalType type = new RentalType();
        type.setName("Модуль");

        Map<UUID, RentalAttributeValue> values = new LinkedHashMap<>();
        values.put(UUID.randomUUID(), stringValue("WIDTH", "6"));
        values.put(UUID.randomUUID(), stringValue("HEIGHT", "2.4"));

        assertThat(formatter.displayName(type, values))
                .isEqualTo("Модуль");
    }

    private RentalAttributeValue numberValue(String code, double number) {
        RentalAttributeDefinition definition = new RentalAttributeDefinition();
        definition.setCode(code);
        RentalAttributeValue value = new RentalAttributeValue();
        value.setAttributeDefinition(definition);
        value.setValueNumber(number);
        return value;
    }

    private RentalAttributeValue optionValue(String code, String optionCode, String optionName) {
        RentalAttributeDefinition definition = new RentalAttributeDefinition();
        definition.setCode(code);
        RentalAttributeOption option = new RentalAttributeOption();
        option.setCode(optionCode);
        option.setName(optionName);
        RentalAttributeValue value = new RentalAttributeValue();
        value.setAttributeDefinition(definition);
        value.setValueOption(option);
        return value;
    }

    private RentalAttributeValue stringValue(String code, String valueString) {
        RentalAttributeDefinition definition = new RentalAttributeDefinition();
        definition.setCode(code);
        RentalAttributeValue value = new RentalAttributeValue();
        value.setAttributeDefinition(definition);
        value.setValueString(valueString);
        return value;
    }
}
