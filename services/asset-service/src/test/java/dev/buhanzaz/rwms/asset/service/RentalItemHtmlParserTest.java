package dev.buhanzaz.rwms.asset.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RentalItemHtmlParserTest {
  private final RentalItemHtmlParser parser = new RentalItemHtmlParser(new ObjectMapper());

  @Test
  void parsesOnlyTheRegistryCellsAndBuildsSafeSuggestions() {
    String html =
        """
        <html><body>
          <script>throw new Error("must never execute")</script>
          <table id="other"><tr class="bootcamp-row" data-bc-id="wrong"></tr></table>
          <table id="city-bootcamps-table">
            <tr class="bootcamp-row" data-bc-id="19180">
              <td class="bc-number">140385</td>
              %s
            </tr>
          </table>
        </body></html>
        """
            .formatted(
                cells(
                    "Блок-контейнер № 1, 6,0 м ДВП эл-ка",
                    "",
                    "",
                    "",
                    "0",
                    "Ремонт",
                    "https://disk.yandex.ru/d/jSQL-xSlmXWAig"));

    var parsed = parser.parse(html.getBytes(java.nio.charset.StandardCharsets.UTF_8));

    assertThat(parsed.rows()).hasSize(1);
    var row = parsed.rows().getFirst();
    assertThat(row.sourceRowId()).isEqualTo("19180");
    assertThat(row.proposedNumber()).isEqualTo("140385");
    assertThat(row.rentalType().suggestedValue()).isEqualTo("БК-1");
    assertThat(row.dimension().suggestedValue()).isEqualTo("2.4x6");
    assertThat(row.finishing().suggestedValue()).isEqualTo("ДВП");
    assertThat(row.characteristics())
        .extracting(value -> value.suggestedValue())
        .containsExactly("эл-ка");
    assertThat(row.proposedStatus()).isEqualTo(RentalItemStatus.REPAIR);
    assertThat(row.photoPublicKey()).isEqualTo("jSQL-xSlmXWAig");
    assertThat(row.invalid()).isFalse();
  }

  @Test
  void treatsMissingTypeAndDimensionsAsOptionalWhileKeepingFinishingReviewable() {
    String html =
        """
        <table id="city-bootcamps-table">
          <tr class="bootcamp-row" data-bc-id="1">
            <td class="bc-number">100001</td>
            %s
          </tr>
        </table>
        """
            .formatted(cells("БК-2", "", "", "", "1", "Свободная", ""));

    var row =
        parser
            .parse(html.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .rows()
            .getFirst();

    assertThat(row.rentalType().suggestedValue()).isEqualTo("БК-2");
    assertThat(row.dimension().suggestedValue()).isNull();
    assertThat(row.finishing().suggestedValue()).isNull();
    assertThat(row.characteristics()).isEmpty();
    assertThat(row.diagnostics())
        .extracting(value -> value.code())
        .contains("FINISHING_REQUIRES_MAPPING")
        .doesNotContain("TYPE_REQUIRES_MAPPING", "DIMENSION_REQUIRES_MAPPING");
    assertThat(row.invalid()).isTrue();
    assertThat(row.requiresManualReview()).isFalse();
  }

  @Test
  void normalizesCatalogWordsAndDimensionsEmbeddedInSourceLabels() {
    String html =
        """
        <table id="city-bootcamps-table">
          <tr class="bootcamp-row" data-bc-id="1">
            <td class="bc-number">100011</td>
            %s
          </tr>
          <tr class="bootcamp-row" data-bc-id="2">
            <td class="bc-number">100012</td>
            %s
          </tr>
        </table>
        """
            .formatted(
                cells(
                    "БК-Сан. Блок ПВХ Не эконом КК. УЗО.",
                    "",
                    "",
                    "",
                    "0",
                    "Свободная",
                    ""),
                cells(
                    "Пост охраны 2.4х2.4 ДВП",
                    "6*2,4*2,6",
                    "",
                    "",
                    "0",
                    "Свободная",
                    ""));

    var rows =
        parser
            .parse(html.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .rows();

    assertThat(rows.get(0).rentalType().suggestedValue()).isEqualTo("БК-Санблок");
    assertThat(rows.get(0).finishing().suggestedValue()).isEqualTo("ПВХ");
    assertThat(rows.get(0).characteristics())
        .extracting(value -> value.suggestedValue())
        .noneMatch(value -> value.contains("Сан"));
    assertThat(rows.get(1).rentalType().suggestedValue()).isEqualTo("БК-Пост охраны");
    assertThat(rows.get(1).dimension().suggestedValue()).isEqualTo("2.4x6");
    assertThat(rows.get(1).finishing().suggestedValue()).isEqualTo("ДВП");
  }

  @Test
  void keepsStructurallyInvalidRowsInManualReview() {
    String html =
        """
        <table id="city-bootcamps-table">
          <tr class="bootcamp-row" data-bc-id="invalid source id">
            <td class="bc-number">100002</td>
            %s
          </tr>
        </table>
        """
            .formatted(
                cells(
                    "БК-1, 6,0 м ДВП",
                    "2.4x6",
                    "ДВП",
                    "Новая",
                    "0",
                    "Свободная",
                    ""));

    var row =
        parser
            .parse(html.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .rows()
            .getFirst();

    assertThat(row.diagnostics())
        .extracting(value -> value.code())
        .contains("INVALID_SOURCE_ROW_ID");
    assertThat(row.requiresManualReview()).isTrue();
  }

  @Test
  void parsesTheProvidedFixtureWithoutPersistingIt() throws Exception {
    Path fixture = findFixture();
    Assumptions.assumeTrue(Files.isRegularFile(fixture), "root test.html is optional");

    var parsed = parser.parse(Files.readAllBytes(fixture));

    assertThat(parsed.rows()).hasSize(755);
    assertThat(parsed.rows()).extracting(value -> value.sourceRowId()).doesNotHaveDuplicates();
    assertThat(parsed.rows().stream().filter(row -> row.photoPublicKey() != null))
        .hasSize(415);
  }

  private static Path findFixture() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    for (int depth = 0; depth < 5 && current != null; depth += 1) {
      Path candidate = current.resolve("test.html");
      if (Files.isRegularFile(candidate)) return candidate;
      current = current.getParent();
    }
    return Path.of("test.html");
  }

  private static String cells(
      String type,
      String dimension,
      String finishing,
      String category,
      String linoleum,
      String status,
      String photo) {
    return """
        <td data-field="UF_TYPE_ID" data-value="237">%s</td>
        <td data-field="UF_GABARIT_ID" data-value="0">%s</td>
        <td data-field="UF_OTDELKA_ID" data-value="0">%s</td>
        <td data-field="UF_CATEGORY_ID" data-value="0">%s</td>
        <td data-field="UF_CHARS_IDS" data-value=""></td>
        <td data-field="UF_LINOLEUM" data-value="%s">%s</td>
        <td data-field="UF_STATE_STORAGE" data-value=""></td>
        <td data-field="UF_STATUS_ID" data-value="6">%s</td>
        <td data-field="UF_COMMENT" data-value=""></td>
        <td data-field="UF_PHOTO_URL" data-value="%s"></td>
        <td data-field="UF_FURNITURE_JSON" data-value=""></td>
        <td data-field="UF_SHIPMENT_DATE" data-value=""></td>
        <td data-field="UF_RECEIVED_FROM" data-value=""></td>
        <td data-field="UF_PRICE" data-value="0"></td>
        """
        .formatted(
            type,
            dimension,
            finishing,
            category,
            linoleum,
            "1".equals(linoleum) ? "Да" : "Нет",
            status,
            photo);
  }
}
