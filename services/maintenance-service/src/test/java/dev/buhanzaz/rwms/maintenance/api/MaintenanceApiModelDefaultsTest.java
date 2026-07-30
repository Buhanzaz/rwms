package dev.buhanzaz.rwms.maintenance.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.CatalogNodeInput;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.EstimateLineInput;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.EstimateLineResponse;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.EstimateLineType;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.FreezeInventoryPlanRequest;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.FrozenInventoryPlanSnapshot;
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

  @Test
  void canonicalCatalogEstimateLineCarriesItsFrozenTypeUnitAndNormative()
      throws JacksonException {
    EstimateLineResponse value = mapper.readValue(
        """
        {
          "id":"20000000-0000-0000-0000-000000000001",
          "catalogSnapshot":{
            "catalogVersionId":"30000000-0000-0000-0000-000000000001",
            "nodeId":"40000000-0000-0000-0000-000000000001",
            "nodeType":"WORK",
            "name":"Repair work",
            "unit":"piece",
            "unitPrice":"100.00",
            "durationMinutes":60,
            "routing":{
              "queueId":"50000000-0000-0000-0000-000000000001",
              "queueName":"Repair",
              "queueType":"REPAIR"
            },
            "furnitureEquipment":null,
            "forcesCapitalRepair":false,
            "characteristic":null
          },
          "lineType":"WORK",
          "description":"Repair work",
          "unit":"piece",
          "quantity":"1",
          "unitPrice":"100.00",
          "lineTotal":"100.00",
          "normativeMinutes":60,
          "comment":null,
          "mediaReferences":[]
        }
        """,
        EstimateLineResponse.class);

    assertThat(value.normativeMinutes()).isEqualTo(60);
    assertThat(value.lineType()).isEqualTo(EstimateLineType.WORK);
    assertThat(value.unit()).isEqualTo("piece");
  }

  @Test
  void canonicalCustomEstimateLineCarriesItsTypeUnitAndPositiveNormative()
      throws JacksonException {
    EstimateLineResponse value = mapper.readValue(
        """
        {
          "id":"20000000-0000-0000-0000-000000000002",
          "catalogSnapshot":null,
          "lineType":"WORK",
          "description":"Custom work",
          "unit":"шт.",
          "quantity":"1",
          "unitPrice":"0.00",
          "lineTotal":"0.00",
          "normativeMinutes":15,
          "comment":null,
          "mediaReferences":[]
        }
        """,
        EstimateLineResponse.class);

    assertThat(value.normativeMinutes()).isEqualTo(15);
    assertThat(value.lineType()).isEqualTo(EstimateLineType.WORK);
    assertThat(value.unit()).isEqualTo("шт.");
  }

  @Test
  void estimateLineInputRequiresTheFrozenLineTypeAndUnitProperties() {
    String complete = """
        {
          "id":"20000000-0000-0000-0000-000000000003",
          "catalogSnapshot":null,
          "lineType":"WORK",
          "description":"Custom work",
          "unit":"шт.",
          "quantity":"1",
          "unitPrice":"0.00",
          "normativeMinutes":15,
          "comment":null,
          "mediaReferences":[]
        }
        """;

    assertThatThrownBy(
            () -> mapper.readValue(complete.replace("\"lineType\":\"WORK\",", ""), EstimateLineInput.class))
        .isInstanceOf(JacksonException.class);
    assertThatThrownBy(
            () -> mapper.readValue(complete.replace("\"unit\":\"шт.\",", ""), EstimateLineInput.class))
        .isInstanceOf(JacksonException.class);
  }

  @Test
  void canonicalInventoryPlansCarryPriorityAndExplicitCoverSelection()
      throws JacksonException {
    FreezeInventoryPlanRequest request = mapper.readValue(
        """
        {
          "warehouseId":"10000000-0000-0000-0000-000000000001",
          "inventoryId":"10000000-0000-0000-0000-000000000002",
          "findingId":"10000000-0000-0000-0000-000000000003",
          "sourceRevision":1,
          "mode":"AUTO",
          "lines":[],
          "plan":[],
          "mediaReferences":[],
          "priority":3,
          "coverMediaId":null
        }
        """,
        FreezeInventoryPlanRequest.class);
    FrozenInventoryPlanSnapshot snapshot = mapper.readValue(
        """
        {
          "catalogVersionId":"10000000-0000-0000-0000-000000000004",
          "mode":"AUTO",
          "lines":[],
          "stages":[],
          "moveToRepairRequired":false,
          "moveFromRepairRequired":false,
          "mediaReferences":[],
          "priority":3,
          "coverMediaId":null
        }
        """,
        FrozenInventoryPlanSnapshot.class);

    assertThat(request.priority()).isEqualTo(3);
    assertThat(request.coverMediaId()).isNull();
    assertThat(snapshot.priority()).isEqualTo(3);
    assertThat(snapshot.coverMediaId()).isNull();
  }

  private static String requiredNodeJson(String extra) {
    return """
        {"id":"10000000-0000-0000-0000-000000000001",
         "nodeType":"WORK","name":"Node","active":true,
         "unitPrice":null,"durationMinutes":0,"routing":null%s}
        """.formatted(extra);
  }
}
