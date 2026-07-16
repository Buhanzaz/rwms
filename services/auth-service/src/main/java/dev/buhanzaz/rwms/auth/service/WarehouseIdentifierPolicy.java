package dev.buhanzaz.rwms.auth.service;

import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class WarehouseIdentifierPolicy {

    private static final UUID SPB_WAREHOUSE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MSK_WAREHOUSE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    public UUID canonicalUuid(String value) {
        String candidate = value == null ? "" : value.trim();
        if ("spb".equalsIgnoreCase(candidate)) {
            return SPB_WAREHOUSE_ID;
        }
        if ("msk".equalsIgnoreCase(candidate)) {
            return MSK_WAREHOUSE_ID;
        }
        try {
            UUID warehouseId = UUID.fromString(candidate);
            if (!warehouseId.toString().equals(candidate.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException();
            }
            return warehouseId;
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Идентификатор склада должен быть UUID или известным alias: " + candidate);
        }
    }
}
