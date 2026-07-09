package dev.buhanzaz.wmspanel.service;

import dev.buhanzaz.wmspanel.entity.ReservationSearchSettings;
import io.jmix.core.DataManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationSearchSettingsService {

    public static final int DEFAULT_REFRESH_SECONDS = 10;
    public static final int MIN_REFRESH_SECONDS = 3;
    private static final String DEFAULT_CODE = "DEFAULT";

    private final DataManager dataManager;

    public ReservationSearchSettingsService(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    @Transactional(readOnly = true)
    public ReservationSearchSettings loadOrCreate() {
        return dataManager.load(ReservationSearchSettings.class)
                .query("select e from ReservationSearchSettings e where e.code = :code")
                .parameter("code", DEFAULT_CODE)
                .optional()
                .orElseGet(this::createDefaultTransient);
    }

    @Transactional(readOnly = true)
    public int currentRefreshSeconds() {
        return sanitizeRefreshSeconds(loadOrCreate().getReservationSearchRefreshSeconds());
    }

    @Transactional
    public ReservationSearchSettings save(Integer refreshSeconds) {
        ReservationSearchSettings settings = dataManager.load(ReservationSearchSettings.class)
                .query("select e from ReservationSearchSettings e where e.code = :code")
                .parameter("code", DEFAULT_CODE)
                .optional()
                .orElseGet(this::createDefaultTransient);
        settings.setCode(DEFAULT_CODE);
        settings.setReservationSearchRefreshSeconds(sanitizeRefreshSeconds(refreshSeconds));
        return dataManager.save(settings);
    }

    public int sanitizeRefreshSeconds(Integer refreshSeconds) {
        if (refreshSeconds == null) {
            return DEFAULT_REFRESH_SECONDS;
        }
        if (refreshSeconds <= 0) {
            return 0;
        }
        return Math.max(MIN_REFRESH_SECONDS, refreshSeconds);
    }

    private ReservationSearchSettings createDefaultTransient() {
        ReservationSearchSettings settings = dataManager.create(ReservationSearchSettings.class);
        settings.setCode(DEFAULT_CODE);
        settings.setReservationSearchRefreshSeconds(DEFAULT_REFRESH_SECONDS);
        return settings;
    }
}
