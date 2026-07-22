package dev.buhanzaz.rwms.maintenance.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.CatalogNodeInput;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

class MaintenanceApiModelDefaultsTest {
  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void omittedCatalogFlagsReceiveThePublishedDefaults() throws JacksonException {
    CatalogNodeInput value = mapper.readValue(requiredNodeJson(""), CatalogNodeInput.class);

    assertThat(value.includeInEstimate()).isTrue();
    assertThat(value.commonItem()).isFalse();
    assertThat(value.showInMainMenu()).isFalse();
  }

  @Test
  void explicitNullCatalogFlagsAreRejectedInsteadOfReceivingDefaults() {
    for (String field : List.of("includeInEstimate", "commonItem", "showInMainMenu")) {
      assertThatThrownBy(() -> mapper.readValue(
          requiredNodeJson(",\"" + field + "\":null"), CatalogNodeInput.class))
          .as(field)
          .isInstanceOf(JacksonException.class);
    }
  }

  @Test
  void requiredActiveFlagCannotBeMissingOrNull() {
    String missing = requiredNodeJson("").replace(",\"active\":true", "");
    String explicitNull = requiredNodeJson("").replace("\"active\":true", "\"active\":null");

    assertThatThrownBy(() -> mapper.readValue(missing, CatalogNodeInput.class))
        .isInstanceOf(JacksonException.class);
    assertThatThrownBy(() -> mapper.readValue(explicitNull, CatalogNodeInput.class))
        .isInstanceOf(JacksonException.class);
  }

  private static String requiredNodeJson(String extra) {
    return """
        {"id":"10000000-0000-0000-0000-000000000001",
         "code":"NODE","nodeType":"WORK","name":"Node","active":true,
         "unitPrice":null,"durationMinutes":0,"routing":null,
         "references":[]%s}
        """.formatted(extra);
  }
}
