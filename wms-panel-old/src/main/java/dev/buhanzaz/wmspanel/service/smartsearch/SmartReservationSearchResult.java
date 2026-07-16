package dev.buhanzaz.wmspanel.service.smartsearch;

import dev.buhanzaz.wmspanel.service.ReservationService;

import java.util.List;

public record SmartReservationSearchResult(
        String originalText,
        ReservationService.ReservationSearchCriteria criteria,
        List<SmartReservationRecognizedToken> recognizedTokens,
        boolean aiUsed,
        String providerName,
        List<String> warnings) {

    public SmartReservationSearchResult {
        criteria = criteria == null
                ? new ReservationService.ReservationSearchCriteria(null, null, null, null, null, 1, null, null, List.of(), List.of())
                : criteria;
        recognizedTokens = recognizedTokens == null ? List.of() : List.copyOf(recognizedTokens);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        providerName = providerName == null || providerName.isBlank() ? "NONE" : providerName.trim();
        originalText = originalText == null ? "" : originalText.trim();
    }

    public SmartReservationSearchResult withWarnings(List<String> extraWarnings) {
        if (extraWarnings == null || extraWarnings.isEmpty()) {
            return this;
        }
        List<String> mergedWarnings = new java.util.ArrayList<>(warnings);
        mergedWarnings.addAll(extraWarnings);
        return new SmartReservationSearchResult(
                originalText,
                criteria,
                recognizedTokens,
                aiUsed,
                providerName,
                mergedWarnings);
    }

    public boolean hasMeaningfulCriteria() {
        if (criteria == null) {
            return false;
        }
        if (criteria.warehouse() != null
                || criteria.category() != null
                || criteria.rentalClass() != null
                || criteria.type() != null
                || criteria.condition() != null
                || (criteria.attributeCriteria() != null && !criteria.attributeCriteria().isEmpty())
                || (criteria.tags() != null && !criteria.tags().isEmpty())
                || criteria.text() != null && !criteria.text().isBlank()) {
            return true;
        }
        return criteria.quantity() != null && criteria.quantity() > 1;
    }
}
