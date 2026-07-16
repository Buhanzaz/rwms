package dev.buhanzaz.wmspanel.service.smartsearch;

public record SmartReservationRecognizedToken(
        String key,
        SmartReservationTokenType type,
        String label,
        boolean removable,
        String note) {
}
