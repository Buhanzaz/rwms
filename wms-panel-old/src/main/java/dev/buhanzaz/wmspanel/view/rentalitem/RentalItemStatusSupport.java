package dev.buhanzaz.wmspanel.view.rentalitem;

import com.vaadin.flow.component.icon.VaadinIcon;

import java.util.List;

public final class RentalItemStatusSupport {

    public static final List<String> STATUSES = List.of(
            "READY",
            "TEMP_RESERVED",
            "RESERVED",
            "IN_RENT",
            "NEED_INSPECTION",
            "WAITING_ESTIMATE_CONFIRMATION",
            "WAITING_REPAIR",
            "IN_REPAIR",
            "WAITING_REPAIR_CHECK",
            "IN_CAP_REPAIR"
    );

    public static final List<String> NEW_ITEM_STATUSES = List.of("READY", "NEED_INSPECTION");

    private RentalItemStatusSupport() {
    }

    public static StatusPresentation presentationFor(String status) {
        if (status == null || status.isBlank()) {
            return new StatusPresentation("Не задан", "status-muted", VaadinIcon.MINUS);
        }

        return switch (status) {
            case "READY" -> new StatusPresentation("Готов", "status-free", VaadinIcon.CHECK);
            case "TEMP_RESERVED" -> new StatusPresentation("Временный резерв", "status-reserved", VaadinIcon.CLOCK);
            case "RESERVED" -> new StatusPresentation("Резерв", "status-reserved", VaadinIcon.LOCK);
            case "IN_RENT" -> new StatusPresentation("В аренде", "status-muted", VaadinIcon.SIGN_IN);
            case "NEED_INSPECTION" -> new StatusPresentation("Требует осмотра", "status-inspection", VaadinIcon.WRENCH);
            case "WAITING_ESTIMATE_CONFIRMATION" -> new StatusPresentation("Ожидает подтверждения сметы", "status-inspection", VaadinIcon.CLOCK);
            case "WAITING_REPAIR" -> new StatusPresentation("Ожидает ремонта", "status-repair", VaadinIcon.CLOCK);
            case "IN_REPAIR" -> new StatusPresentation("В процессе ремонта", "status-repair", VaadinIcon.TOOLS);
            case "WAITING_REPAIR_CHECK" -> new StatusPresentation("Ожидание проверки ремонта", "status-after-repair", VaadinIcon.SEARCH);
            case "IN_CAP_REPAIR" -> new StatusPresentation("В капремонте", "status-capital-repair", VaadinIcon.WARNING);
            case "Свободная" -> new StatusPresentation("Готов", "status-free", VaadinIcon.CHECK);
            case "На доработку" -> new StatusPresentation("Требует осмотра", "status-inspection", VaadinIcon.WRENCH);
            case "Временный резерв" -> new StatusPresentation("Временный резерв", "status-reserved", VaadinIcon.CLOCK);
            case "Резерв клиента" -> new StatusPresentation("Резерв", "status-reserved", VaadinIcon.LOCK);
            case "После аренды" -> new StatusPresentation("В аренде", "status-muted", VaadinIcon.SIGN_IN);
            case "Ремонт" -> new StatusPresentation("В процессе ремонта", "status-repair", VaadinIcon.TOOLS);
            case "Ожидает ремонта" -> new StatusPresentation("Ожидает ремонта", "status-repair", VaadinIcon.CLOCK);
            case "Ожидание проверки ремонта" -> new StatusPresentation("Ожидание проверки ремонта", "status-after-repair", VaadinIcon.SEARCH);
            case "Капитальный ремонт" -> new StatusPresentation("В капремонте", "status-capital-repair", VaadinIcon.WARNING);
            default -> new StatusPresentation(status, "status-muted", VaadinIcon.INFO_CIRCLE);
        };
    }

    public static boolean isReadyLike(String status) {
        return "READY".equals(status) || "Свободная".equals(status);
    }

    public static boolean isTemporaryReservedLike(String status) {
        return "TEMP_RESERVED".equals(status) || "Временный резерв".equals(status);
    }

    public static boolean isReservedLike(String status) {
        return "RESERVED".equals(status) || "Резерв клиента".equals(status);
    }

    public record StatusPresentation(String label, String className, VaadinIcon icon) {
    }
}
