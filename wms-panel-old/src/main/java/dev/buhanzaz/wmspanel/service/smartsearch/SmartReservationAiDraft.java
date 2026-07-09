package dev.buhanzaz.wmspanel.service.smartsearch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SmartReservationAiDraft(
        String warehouseCode,
        String categoryCode,
        @JsonAlias("subcategoryCode") String classCode,
        String typeCode,
        String conditionCode,
        Integer quantity,
        String freeText,
        List<SmartReservationAiAttributeDraft> attributeCriteria,
        List<String> tagCodes,
        List<String> warnings) {

    public SmartReservationAiDraft {
        attributeCriteria = attributeCriteria == null ? List.of() : List.copyOf(attributeCriteria);
        tagCodes = tagCodes == null ? List.of() : List.copyOf(tagCodes);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        warehouseCode = normalize(warehouseCode);
        categoryCode = normalize(categoryCode);
        classCode = normalize(classCode);
        typeCode = normalize(typeCode);
        conditionCode = normalize(conditionCode);
        freeText = normalize(freeText);
    }

    public String subcategoryCode() {
        return classCode;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}
