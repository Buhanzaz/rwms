package dev.buhanzaz.rwms.logistics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Pins the additive CustomerApp post-checkout lifecycle surface in the canonical OpenAPI file. */
class CustomerBookingLifecycleContractTest {
  @Test
  void contractExposesFencedCancelSearchAndAtomicReschedule() throws Exception {
    Map<String, Object> document = openApi();
    Map<String, Object> paths = child(document, "paths");
    assertThat(paths)
        .containsKeys(
            "/api/logistics/customer/v1/bookings/{bookingId}/cancel",
            "/api/logistics/customer/v1/bookings/{bookingId}/delivery-slots/search",
            "/api/logistics/customer/v1/bookings/{bookingId}/reschedule");

    Map<String, Object> cancel =
        child(child(paths, "/api/logistics/customer/v1/bookings/{bookingId}/cancel"), "post");
    Map<String, Object> reschedule =
        child(child(paths, "/api/logistics/customer/v1/bookings/{bookingId}/reschedule"), "post");
    assertThat(parameterRefs(cancel)).contains("#/components/parameters/IdempotencyKey");
    assertThat(parameterRefs(reschedule)).contains("#/components/parameters/IdempotencyKey");
    assertThat(cancel.get("operationId")).isEqualTo("cancelCustomerBooking");
    assertThat(reschedule.get("operationId")).isEqualTo("rescheduleCustomerBooking");

    Map<String, Object> schemas = child(child(document, "components"), "schemas");
    assertThat(schemas)
        .containsKeys(
            "CancelCustomerBookingRequest",
            "SearchCustomerBookingRescheduleRequest",
            "RescheduleCustomerBookingRequest");
    assertThat(required(child(schemas, "CancelCustomerBookingRequest")))
        .containsExactly("expectedVersion");
    assertThat(required(child(schemas, "RescheduleCustomerBookingRequest")))
        .containsExactly("expectedVersion", "slotId", "slotVersion");

    Map<String, Object> booking = child(schemas, "CustomerBooking");
    assertThat(required(booking)).contains("version", "cancellationFeeRubles");
    assertThat(child(child(booking, "properties"), "status").get("enum"))
        .asList()
        .contains("CANCELLATION_PENDING", "CANCELLED");
    assertThat(child(child(booking, "properties"), "cancellationFeeRubles").get("type"))
        .asList()
        .containsExactly("integer", "null");
  }

  @SuppressWarnings("unchecked")
  private static List<String> parameterRefs(Map<String, Object> operation) {
    return ((List<Map<String, Object>>) operation.get("parameters"))
        .stream().map(parameter -> (String) parameter.get("$ref")).toList();
  }

  @SuppressWarnings("unchecked")
  private static List<String> required(Map<String, Object> schema) {
    return (List<String>) schema.get("required");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> child(Map<String, Object> parent, String name) {
    return (Map<String, Object>) parent.get(name);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> openApi() throws Exception {
    Path contract =
        Path.of(System.getProperty("rwms.contracts.dir"), "openapi/logistics-service.yaml");
    try (InputStream input = Files.newInputStream(contract)) {
      return new Yaml().load(input);
    }
  }
}
