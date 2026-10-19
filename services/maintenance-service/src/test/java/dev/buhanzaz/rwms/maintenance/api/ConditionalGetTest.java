package dev.buhanzaz.rwms.maintenance.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ConditionalGetTest {
  @Test
  void returnsTheCachedRepresentationOnlyWhenItsWeakValidatorMatches() {
    Map<String, Object> body = Map.of("revision", 7, "active", true);

    ResponseEntity<Map<String, Object>> initial = ConditionalGet.response("catalog", body, null);
    String tag = initial.getHeaders().getETag();
    ResponseEntity<Map<String, Object>> unchanged = ConditionalGet.response("catalog", body, tag);

    assertThat(initial.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(initial.getBody()).isEqualTo(body);
    assertThat(tag).startsWith("W/\"").endsWith("\"");
    assertThat(unchanged.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    assertThat(unchanged.getBody()).isNull();
    assertThat(unchanged.getHeaders().getETag()).isEqualTo(tag);
  }

  @Test
  void acceptsListValidatorsAndReturnsTheRepresentationWhenItChanged() {
    Map<String, Object> initialBody = Map.of("revision", 7);
    String tag = ConditionalGet.response("catalog", initialBody, null).getHeaders().getETag();

    ResponseEntity<Map<String, Object>> matching = ConditionalGet.response(
        "catalog",
        initialBody,
        "\"other\", " + tag);
    ResponseEntity<Map<String, Object>> changed = ConditionalGet.response(
        "catalog",
        Map.of("revision", 8),
        tag);

    assertThat(matching.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(changed.getBody()).containsEntry("revision", 8);
  }
}
