package dev.buhanzaz.rwms.logistics.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.logistics.inquiry.service.RentalSettingsService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.task.scheduling.enabled=false",
      "rwms.platform.kafka.enabled=false",
      "rwms.logistics.rental-inquiry.outbox-enabled=false",
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RentalSettingsIntegrationTest {
  private static final String PATH = "/api/logistics/v1/settings/rental";
  private static final UUID ADMIN = UUID.fromString("00000000-0000-4000-8000-000000007101");

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired RentalSettingsService settings;

  @BeforeEach
  void resetSettings() {
    jdbc.update(
        "update rental_settings set version=4, chat_selection_hold_minutes=10,"
            + " manual_booking_hold_minutes=60, presentation_hold_minutes=90,"
            + " draft_reservation_hold_minutes=1440, late_change_notice_days=2,"
            + " late_change_fee_mode=null, late_change_fee_value=null, rental_support_phone=null");
  }

  @Test
  void mapsExactPolicyThroughHttpJpaAndReadOnlySnapshot() throws Exception {
    mvc.perform(get(PATH).with(actor("SYSTEM_ADMIN", false)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lateChangeNoticeDays").value(2))
        .andExpect(jsonPath("$.lateChangeFeeMode").isEmpty())
        .andExpect(jsonPath("$.lateChangeFeeValue").isEmpty());
    mvc.perform(
            put(PATH)
                .with(actor("SYSTEM_ADMIN", true))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request("FIXED", "9223372036854775807"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(5))
        .andExpect(jsonPath("$.lateChangeFeeValue").value("9223372036854775807"))
        .andExpect(jsonPath("$.manualBookingHoldMinutes").value(60))
        .andExpect(jsonPath("$.rentalSupportPhone").value("+74951234567"));
    mvc.perform(get(PATH).with(actor("SYSTEM_ADMIN", false)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lateChangeFeeValue").value("9223372036854775807.00"));
    var policy = settings.lateChangePolicy();
    assertThat(policy.settingsVersion()).isEqualTo(5);
    assertThat(policy.feeValue()).isEqualByComparingTo("9223372036854775807");
    assertThat(policy.supportPhone()).isEqualTo("+74951234567");
  }

  @Test
  void versionConflictDoesNotOverwriteConfiguredPolicy() throws Exception {
    Map<String, Object> request = request("PERCENT", "12.25");
    mvc.perform(
            put(PATH)
                .with(actor("WMS_ADMIN", true))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)))
        .andExpect(status().isOk());
    request.put("lateChangeFeeValue", "50");
    mvc.perform(
            put(PATH)
                .with(actor("WMS_ADMIN", true))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SETTINGS_VERSION_CONFLICT"));
    assertThat(
            jdbc.queryForObject(
                "select late_change_fee_value from rental_settings", BigDecimal.class))
        .isEqualByComparingTo("12.25");
  }

  @Test
  void requiresAdminRoleWriteScopeAndAuthentication() throws Exception {
    String body = json.writeValueAsString(request("FIXED", "1500"));
    mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            put(PATH)
                .with(actor("SYSTEM_ADMIN", false))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            put(PATH)
                .with(actor("VIEWER", true))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    assertThat(jdbc.queryForObject("select version from rental_settings", Long.class)).isEqualTo(4);
  }

  @ParameterizedTest
  @CsvSource(
      value = {
        "FIXED, 1.25",
        "FIXED, 9223372036854775808",
        "PERCENT, 100.01",
        "PERCENT, 1.001",
        "PERCENT, -1",
        "FIXED, NULL",
        "NULL, 0",
        "UNKNOWN, 10"
      },
      nullValues = "NULL")
  void rejectsInvalidOrIncompletePolicy(String mode, String fee) throws Exception {
    mvc.perform(
            put(PATH)
                .with(actor("SYSTEM_ADMIN", true))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request(mode, fee))))
        .andExpect(status().isBadRequest());
    assertThat(jdbc.queryForObject("select version from rental_settings", Long.class)).isEqualTo(4);
  }

  @Test
  void rejectsMissingFieldsButAllowsExplicitUnconfiguredPolicy() throws Exception {
    Map<String, Object> request = request(null, null);
    request.remove("lateChangeFeeValue");
    mvc.perform(
            put(PATH)
                .with(actor("SYSTEM_ADMIN", true))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)))
        .andExpect(status().isBadRequest());
    request.put("lateChangeFeeValue", null);
    request.put("rentalSupportPhone", null);
    mvc.perform(
            put(PATH)
                .with(actor("SYSTEM_ADMIN", true))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lateChangeFeeValue").isEmpty())
        .andExpect(jsonPath("$.rentalSupportPhone").isEmpty());
  }

  private Map<String, Object> request(String mode, String value) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedVersion", 4);
    body.put("chatSelectionHoldMinutes", 10);
    body.put("manualBookingHoldMinutes", 60);
    body.put("presentationHoldMinutes", 90);
    body.put("draftReservationHoldMinutes", 1440);
    body.put("lateChangeNoticeDays", 3);
    body.put("lateChangeFeeMode", mode);
    body.put("lateChangeFeeValue", value);
    body.put("rentalSupportPhone", "+74951234567");
    return body;
  }

  private JwtRequestPostProcessor actor(String role, boolean write) {
    String scope = write ? "rwms.read rwms.write" : "rwms.read";
    return jwt()
        .jwt(
            token ->
                token
                    .subject(ADMIN.toString())
                    .claim("principal_type", "USER")
                    .claim("global_role", role)
                    .claim("rentalAccess", true)
                    .claim("scope", scope))
        .authorities(new SimpleGrantedAuthority(write ? "SCOPE_rwms.write" : "SCOPE_rwms.read"));
  }
}
