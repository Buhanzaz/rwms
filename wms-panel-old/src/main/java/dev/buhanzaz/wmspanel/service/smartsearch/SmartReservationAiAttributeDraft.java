package dev.buhanzaz.wmspanel.service.smartsearch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SmartReservationAiAttributeDraft(
        String code,
        List<String> options,
        Double numberValue,
        Boolean booleanValue,
        String textValue) {

    public SmartReservationAiAttributeDraft {
        options = options == null ? List.of() : List.copyOf(options);
        code = normalize(code);
        textValue = normalize(textValue);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}
