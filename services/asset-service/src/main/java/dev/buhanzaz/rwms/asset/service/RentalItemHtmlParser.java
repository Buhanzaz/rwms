package dev.buhanzaz.rwms.asset.service;

import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses only the exported cabin registry table. It never evaluates scripts,
 * follows links, or retains the source document.
 */
@Component
public class RentalItemHtmlParser {
  public static final int MAX_HTML_BYTES = 10 * 1024 * 1024;
  public static final int MAX_ROWS = 5000;
  private static final DateTimeFormatter SOURCE_DATE =
      DateTimeFormatter.ofPattern("dd.MM.uuuu").withLocale(Locale.forLanguageTag("ru-RU"));
  private static final Pattern PUBLIC_YANDEX =
      Pattern.compile("^https://disk\\.yandex\\.ru/d/([A-Za-z0-9_-]{14})/?$");
  private static final Pattern BLOCK_NUMBER =
      Pattern.compile(
          "(?iuU)(?:блок[\\s-]*контейнер\\s*№?\\s*|(?:бк|bk)\\s*[-№]?\\s*)(\\d+)");
  private static final Pattern LENGTH_SIX =
      Pattern.compile("(?iu)(?:^|[^\\d])6(?:[,.]0)?\\s*м(?:$|[^\\p{L}])");
  private static final Pattern DIMENSION_SEQUENCE =
      Pattern.compile(
          "(?iu)(\\d+(?:[,.]\\d+)?)\\s*[xх×*]\\s*(\\d+(?:[,.]\\d+)?)"
              + "(?:\\s*[xх×*]\\s*(\\d+(?:[,.]\\d+)?))?");
  private static final List<String> FINISHINGS =
      List.of("ЛДСП", "ДВП", "ПВХ", "ОСБ", "СМЛО", "Сэндвич", "Вагонка");
  private static final Set<String> EXPECTED_FIELDS =
      Set.of(
          "UF_TYPE_ID",
          "UF_GABARIT_ID",
          "UF_OTDELKA_ID",
          "UF_CATEGORY_ID",
          "UF_CHARS_IDS",
          "UF_LINOLEUM",
          "UF_STATE_STORAGE",
          "UF_STATUS_ID",
          "UF_COMMENT",
          "UF_PHOTO_URL",
          "UF_FURNITURE_JSON",
          "UF_SHIPMENT_DATE",
          "UF_RECEIVED_FROM",
          "UF_PRICE");
  private static final Set<String> GLOBAL_MAPPING_DIAGNOSTICS =
      Set.of(
          "FINISHING_REQUIRES_MAPPING",
          "STATUS_REQUIRES_MAPPING");
  private static final Map<Integer, String> FURNITURE_NAMES =
      Map.of(
          3, "Вешалка",
          6, "Кровать 2-ярусная",
          7, "Лавка",
          9, "Стол обеденный",
          10, "Стол офисный");
  private static final Map<String, RentalItemStatus> STATUS_BY_LABEL =
      Map.ofEntries(
          Map.entry("аренда", RentalItemStatus.RENTED),
          Map.entry("после аренды", RentalItemStatus.AFTER_RENT),
          Map.entry("ремонт", RentalItemStatus.REPAIR),
          Map.entry("продажа бу", RentalItemStatus.USED_SALE),
          Map.entry("резерв", RentalItemStatus.RESERVED),
          Map.entry("бронь", RentalItemStatus.BOOKED),
          Map.entry("свободная", RentalItemStatus.FREE),
          Map.entry("кап.ремонт", RentalItemStatus.CAPITAL_REPAIR),
          Map.entry("кап ремонт", RentalItemStatus.CAPITAL_REPAIR),
          Map.entry("склад", RentalItemStatus.WAREHOUSE),
          Map.entry("продажа", RentalItemStatus.SALE),
          Map.entry("собственные нужды", RentalItemStatus.OWN_NEEDS));

  private final ObjectMapper objectMapper;

  public RentalItemHtmlParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public ParsedImport parse(byte[] html) {
    if (html == null || html.length == 0 || html.length > MAX_HTML_BYTES) {
      throw new IllegalArgumentException("HTML file must contain 1 byte to 10 MiB");
    }
    Document document = Jsoup.parse(new String(html, StandardCharsets.UTF_8));
    Element table = document.selectFirst("table#city-bootcamps-table");
    if (table == null) {
      throw new IllegalArgumentException("HTML does not contain table#city-bootcamps-table");
    }
    List<Element> sourceRows = table.select("tr.bootcamp-row");
    if (sourceRows.isEmpty() || sourceRows.size() > MAX_ROWS) {
      throw new IllegalArgumentException("HTML registry must contain 1 to 5000 cabin rows");
    }
    List<ParsedRow> result = new ArrayList<>(sourceRows.size());
    Set<String> sourceIds = new LinkedHashSet<>();
    for (int index = 0; index < sourceRows.size(); index += 1) {
      ParsedRow row = parseRow(sourceRows.get(index), index);
      if (!sourceIds.add(row.sourceRowId())) {
        throw new IllegalArgumentException("HTML registry contains a duplicate data-bc-id");
      }
      result.add(row);
    }
    return new ParsedImport(List.copyOf(result));
  }

  private ParsedRow parseRow(Element row, int position) {
    List<ParserDiagnostic> diagnostics = new ArrayList<>();
    String sourceRowId = normalized(row.attr("data-bc-id"), 64);
    if (sourceRowId == null || !sourceRowId.matches("^[A-Za-z0-9_-]+$")) {
      sourceRowId = "position-" + position;
      diagnostics.add(error("INVALID_SOURCE_ROW_ID", null));
    }

    Map<String, SourceCell> fields = new LinkedHashMap<>();
    for (Element cell : row.select("td[data-field]")) {
      String field = cell.attr("data-field");
      if (!EXPECTED_FIELDS.contains(field) || fields.containsKey(field)) {
        diagnostics.add(error("INVALID_FIELD_STRUCTURE", field));
        continue;
      }
      fields.put(
          field,
          new SourceCell(normalized(cell.attr("data-value"), 16000), normalized(cell.text(), 4000)));
    }
    if (!fields.keySet().equals(EXPECTED_FIELDS)) {
      diagnostics.add(error("MISSING_EXPECTED_FIELDS", null));
    }

    String sourceNumber = normalized(text(row.selectFirst("td.bc-number")), 512);
    String proposedNumber = null;
    String identityMatchKey = null;
    try {
      proposedNumber = RentalItem.canonicalNumber(sourceNumber);
      identityMatchKey = RentalItem.identityMatchKey(proposedNumber);
    } catch (IllegalArgumentException exception) {
      diagnostics.add(error("INVALID_RENTAL_NUMBER", "number"));
    }

    SourceCell typeCell = field(fields, "UF_TYPE_ID");
    String typeSuggestion = proposedType(typeCell.label());
    SourceValue type = sourceValue(typeCell, typeSuggestion);

    SourceCell dimensionCell = field(fields, "UF_GABARIT_ID");
    String dimensionSuggestion = proposedDimension(dimensionCell.label());
    if (dimensionSuggestion == null && dimensionCell.label() != null) {
      dimensionSuggestion = dimensionCell.label();
    }
    if (dimensionSuggestion == null) dimensionSuggestion = proposedDimension(typeCell.label());
    SourceValue dimension = sourceValue(dimensionCell, dimensionSuggestion);

    SourceCell finishingCell = field(fields, "UF_OTDELKA_ID");
    String finishingSuggestion =
        finishingCell.label() == null ? proposedFinishing(typeCell.label()) : finishingCell.label();
    if (finishingSuggestion == null) {
      diagnostics.add(error("FINISHING_REQUIRES_MAPPING", "UF_OTDELKA_ID"));
    }
    SourceValue finishing = sourceValue(finishingCell, finishingSuggestion);

    SourceCell categoryCell = field(fields, "UF_CATEGORY_ID");
    SourceValue category = sourceValue(categoryCell, categoryCell.label());

    List<SourceValue> characteristics =
        proposedCharacteristics(field(fields, "UF_CHARS_IDS"), typeCell.label());
    Boolean linoleum = booleanValue(field(fields, "UF_LINOLEUM"), diagnostics);
    String storageState = value(field(fields, "UF_STATE_STORAGE"));

    SourceCell statusCell = field(fields, "UF_STATUS_ID");
    String statusLabel =
        statusCell.label() == null ? statusCell.rawValue() : statusCell.label();
    RentalItemStatus proposedStatus =
        statusLabel == null ? null : STATUS_BY_LABEL.get(normalizedKey(statusLabel));
    if (proposedStatus == null) diagnostics.add(error("STATUS_REQUIRES_MAPPING", "UF_STATUS_ID"));

    String photoPublicKey = photoKey(field(fields, "UF_PHOTO_URL"), diagnostics);
    List<FurnitureLine> furniture =
        furniture(field(fields, "UF_FURNITURE_JSON"), diagnostics);
    LocalDate shipmentDate = date(field(fields, "UF_SHIPMENT_DATE"), diagnostics);
    BigDecimal price = price(field(fields, "UF_PRICE"), diagnostics);

    return new ParsedRow(
        sourceRowId,
        position,
        sourceNumber,
        proposedNumber,
        identityMatchKey,
        type,
        dimension,
        finishing,
        category,
        characteristics,
        linoleum,
        storageState,
        sourceValue(statusCell, proposedStatus == null ? null : proposedStatus.name()),
        proposedStatus,
        value(field(fields, "UF_COMMENT")),
        photoPublicKey,
        furniture,
        shipmentDate,
        value(field(fields, "UF_RECEIVED_FROM")),
        price,
        List.copyOf(diagnostics));
  }

  private List<FurnitureLine> furniture(
      SourceCell cell, List<ParserDiagnostic> diagnostics) {
    if (cell.rawValue() == null) return List.of();
    try {
      JsonNode root = objectMapper.readTree(cell.rawValue());
      if (root == null || !root.isArray() || root.size() > 50) {
        diagnostics.add(error("INVALID_FURNITURE_JSON", "UF_FURNITURE_JSON"));
        return List.of();
      }
      List<FurnitureLine> values = new ArrayList<>();
      for (JsonNode entry : root) {
        JsonNode idNode = entry.get("id");
        JsonNode quantityNode = entry.get("qty");
        if (!entry.isObject()
            || idNode == null
            || !idNode.isIntegralNumber()
            || quantityNode == null
            || !quantityNode.isIntegralNumber()) {
          diagnostics.add(error("INVALID_FURNITURE_JSON", "UF_FURNITURE_JSON"));
          return List.of();
        }
        int sourceId = idNode.intValue();
        int quantity = quantityNode.intValue();
        if (quantity < 1 || quantity > 1000) {
          diagnostics.add(error("INVALID_FURNITURE_QUANTITY", "UF_FURNITURE_JSON"));
          return List.of();
        }
        String name = FURNITURE_NAMES.get(sourceId);
        if (name == null) {
          name = "ID " + sourceId;
          diagnostics.add(warning("UNKNOWN_FURNITURE_ID", "UF_FURNITURE_JSON"));
        }
        values.add(new FurnitureLine(Integer.toString(sourceId), name, quantity));
      }
      return List.copyOf(values);
    } catch (JacksonException exception) {
      diagnostics.add(error("INVALID_FURNITURE_JSON", "UF_FURNITURE_JSON"));
      return List.of();
    }
  }

  private static Boolean booleanValue(
      SourceCell cell, List<ParserDiagnostic> diagnostics) {
    String raw = value(cell);
    if (raw == null) return null;
    return switch (normalizedKey(raw)) {
      case "1", "true", "да", "yes" -> true;
      case "0", "false", "нет", "no" -> false;
      default -> {
        diagnostics.add(error("INVALID_BOOLEAN", "UF_LINOLEUM"));
        yield null;
      }
    };
  }

  private static LocalDate date(SourceCell cell, List<ParserDiagnostic> diagnostics) {
    String raw = value(cell);
    if (raw == null) return null;
    try {
      return LocalDate.parse(raw, SOURCE_DATE);
    } catch (DateTimeParseException exception) {
      diagnostics.add(error("INVALID_SHIPMENT_DATE", "UF_SHIPMENT_DATE"));
      return null;
    }
  }

  private static BigDecimal price(SourceCell cell, List<ParserDiagnostic> diagnostics) {
    String raw = value(cell);
    if (raw == null) return null;
    try {
      BigDecimal parsed = new BigDecimal(raw.replace(" ", "").replace(',', '.'));
      if (parsed.signum() <= 0) return null;
      if (parsed.scale() > 2 || parsed.precision() > 14) throw new NumberFormatException();
      return parsed;
    } catch (NumberFormatException exception) {
      diagnostics.add(error("INVALID_PRICE", "UF_PRICE"));
      return null;
    }
  }

  private static String photoKey(
      SourceCell cell, List<ParserDiagnostic> diagnostics) {
    String raw = value(cell);
    if (raw == null) return null;
    Matcher match = PUBLIC_YANDEX.matcher(raw);
    if (!match.matches()) {
      diagnostics.add(warning("INVALID_YANDEX_PUBLIC_URL", "UF_PHOTO_URL"));
      return null;
    }
    return match.group(1);
  }

  private static String proposedType(String value) {
    if (value == null) return null;
    String key = normalizedKey(value);
    String compactKey = key.replaceAll("[^\\p{L}\\p{N}]+", "");
    if (compactKey.contains("санблок")) return "БК-Санблок";
    if (key.contains("пост охраны")) return "БК-Пост охраны";
    if (key.contains("модуль") && key.matches(".*(?:из\\s*)?3(?:х|\\b).*")) {
      return "БК-Модуль из 3";
    }
    if (key.contains("модуль") && key.matches(".*(?:из\\s*)?2(?:х|\\b).*")) {
      return "БК-Модуль из 2х";
    }
    if (key.contains("склад")) return "БК-Склад";
    Matcher number = BLOCK_NUMBER.matcher(value);
    return number.find() ? "БК-" + Integer.parseInt(number.group(1)) : null;
  }

  private static String proposedDimension(String value) {
    if (value == null) return null;
    Matcher dimensions = DIMENSION_SEQUENCE.matcher(value);
    if (dimensions.find()) {
      return canonicalDimension(dimensions.group(1), dimensions.group(2));
    }
    return LENGTH_SIX.matcher(value).find() ? "2.4x6" : null;
  }

  private static String canonicalDimension(String first, String second) {
    String left = canonicalDecimal(first);
    String right = canonicalDecimal(second);
    if ("2.4".equals(left)) return left + "x" + right;
    if ("2.4".equals(right)) return right + "x" + left;
    if ("6".equals(left)) return right + "x" + left;
    return left + "x" + right;
  }

  private static String canonicalDecimal(String value) {
    return new BigDecimal(value.replace(',', '.')).stripTrailingZeros().toPlainString();
  }

  private static String proposedFinishing(String value) {
    if (value == null) return null;
    String key = normalizedKey(value);
    for (String finishing : FINISHINGS) {
      if (key.contains(normalizedKey(finishing))) return finishing;
    }
    return null;
  }

  private static List<SourceValue> proposedCharacteristics(SourceCell cell, String typeLabel) {
    List<SourceValue> values = new ArrayList<>();
    if (cell.label() != null) {
      for (String token : cell.label().split("\\s*[,;]\\s*")) {
        String normalized = normalized(token, 255);
        if (normalized != null) values.add(new SourceValue(cell.rawValue(), normalized, normalized));
      }
    }
    if (!values.isEmpty() || typeLabel == null) return List.copyOf(values);
    String leftover =
        typeLabel
            .replaceAll(
                "(?iuU)блок[\\s-]*контейнер\\s*№?\\s*\\d+|(?<![\\p{L}\\p{N}])(?:бк|bk)\\s*[-№]?\\s*\\d+",
                " ")
            .replaceAll("(?iuU)(?:бк\\s*[-–—]?\\s*)?пост\\s+охраны", " ")
            .replaceAll("(?iuU)(?:бк\\s*[-–—]?\\s*)?сан\\.?\\s*блок", " ")
            .replaceAll("(?iuU)(?:бк\\s*[-–—]?\\s*)?склад", " ")
            .replaceAll("(?iuU)(?:бк\\s*[-–—]?\\s*)?модуль\\s+из\\s+\\d+х?", " ")
            .replaceAll("(?iuU)модульное\\s+здание", " ")
            .replaceAll("(?iu)6(?:[,.]0)?\\s*м", " ");
    leftover = DIMENSION_SEQUENCE.matcher(leftover).replaceAll(" ");
    for (String finishing : FINISHINGS) {
      leftover = leftover.replaceAll("(?iuU)" + Pattern.quote(finishing), " ");
    }
    leftover = normalized(leftover.replaceAll("[,()]+", " "), 255);
    if (leftover != null
        && !Set.of("блок контейнер", "блок-контейнер").contains(normalizedKey(leftover))) {
      values.add(new SourceValue(null, leftover, leftover));
    }
    return List.copyOf(values);
  }

  private static SourceCell field(Map<String, SourceCell> fields, String name) {
    return fields.getOrDefault(name, new SourceCell(null, null));
  }

  private static SourceValue sourceValue(SourceCell cell, String suggestion) {
    return new SourceValue(cell.rawValue(), cell.label(), normalized(suggestion, 255));
  }

  private static String value(SourceCell cell) {
    return cell.rawValue() == null ? cell.label() : cell.rawValue();
  }

  private static String text(Element element) {
    return element == null ? null : element.text();
  }

  private static String normalized(String value, int maximum) {
    if (value == null) return null;
    String normalized = value.trim().replaceAll("[\\p{Z}\\s]+", " ");
    if (normalized.isEmpty()) return null;
    return normalized.length() > maximum ? normalized.substring(0, maximum) : normalized;
  }

  private static String normalizedKey(String value) {
    String normalized = normalized(value, 4000);
    return normalized == null ? "" : normalized.toLowerCase(Locale.ROOT);
  }

  private static ParserDiagnostic error(String code, String field) {
    return new ParserDiagnostic(DiagnosticSeverity.ERROR, code, field);
  }

  private static ParserDiagnostic warning(String code, String field) {
    return new ParserDiagnostic(DiagnosticSeverity.WARNING, code, field);
  }

  private record SourceCell(String rawValue, String label) {}

  public enum DiagnosticSeverity {
    ERROR,
    WARNING
  }

  public record ParserDiagnostic(DiagnosticSeverity severity, String code, String field) {}

  public record SourceValue(String sourceId, String sourceLabel, String suggestedValue) {}

  public record FurnitureLine(String sourceId, String sourceLabel, int quantity) {}

  public record ParsedRow(
      String sourceRowId,
      int sourcePosition,
      String sourceNumber,
      String proposedNumber,
      String identityMatchKey,
      SourceValue rentalType,
      SourceValue dimension,
      SourceValue finishing,
      SourceValue category,
      List<SourceValue> characteristics,
      Boolean linoleum,
      String storageState,
      SourceValue status,
      RentalItemStatus proposedStatus,
      String comment,
      String photoPublicKey,
      List<FurnitureLine> furniture,
      LocalDate shipmentDate,
      String tenant,
      BigDecimal price,
      List<ParserDiagnostic> diagnostics) {
    public boolean invalid() {
      return diagnostics.stream()
          .anyMatch(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR);
    }

    public boolean requiresManualReview() {
      return diagnostics.stream()
          .anyMatch(
              diagnostic ->
                  diagnostic.severity() == DiagnosticSeverity.ERROR
                      && !GLOBAL_MAPPING_DIAGNOSTICS.contains(diagnostic.code()));
    }

    public int warningCount() {
      return (int)
          diagnostics.stream()
              .filter(diagnostic -> diagnostic.severity() == DiagnosticSeverity.WARNING)
              .count();
    }
  }

  public record ParsedImport(List<ParsedRow> rows) {}
}
