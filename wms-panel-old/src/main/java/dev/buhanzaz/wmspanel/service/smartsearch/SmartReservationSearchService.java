package dev.buhanzaz.wmspanel.service.smartsearch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.buhanzaz.wmspanel.entity.RentalAttributeDefinition;
import dev.buhanzaz.wmspanel.entity.RentalAttributeOption;
import dev.buhanzaz.wmspanel.entity.RentalClassifierAttribute;
import dev.buhanzaz.wmspanel.entity.RentalItemCondition;
import dev.buhanzaz.wmspanel.entity.RentalItemStatus;
import dev.buhanzaz.wmspanel.entity.RentalTag;
import dev.buhanzaz.wmspanel.entity.RentalSubcategory;
import dev.buhanzaz.wmspanel.entity.RentalType;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.service.RentalItemService;
import dev.buhanzaz.wmspanel.service.ReservationService;
import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SmartReservationSearchService {

    private static final String PROVIDER_NAME = "MISTRAL_OPENAI_COMPAT";

    private final ReservationService reservationService;
    private final DataManager dataManager;
    private final RentalItemService rentalItemService;
    private final SmartReservationAiParser aiParser;
    private final SmartReservationAiProperties aiProperties;
    private final Messages messages;
    private final ObjectMapper objectMapper;

    public SmartReservationSearchService(ReservationService reservationService,
                                         DataManager dataManager,
                                         RentalItemService rentalItemService,
                                         SmartReservationAiParser aiParser,
                                         SmartReservationAiProperties aiProperties,
                                         Messages messages,
                                         ObjectMapper objectMapper) {
        this.reservationService = reservationService;
        this.dataManager = dataManager;
        this.rentalItemService = rentalItemService;
        this.aiParser = aiParser;
        this.aiProperties = aiProperties;
        this.messages = messages;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public SmartReservationSearchResult parse(String text) {
        return parse(text, List.of());
    }

    @Transactional(readOnly = true)
    public SmartReservationSearchResult parse(String text, Collection<String> excludedKeys) {
        String original = text == null ? "" : text.trim();
        if (original.isBlank()) {
            return emptyResult(original);
        }
        if (!aiProperties.isConfigured()) {
            throw new SmartReservationSearchUnavailableException(messages.getMessage("smartSearch.unavailable"));
        }

        SearchReferenceData referenceData = loadReferenceData();
        String contextJson = buildContextJson(referenceData);
        SmartReservationAiDraft draft = aiParser.parse(original, contextJson)
                .orElseThrow(() -> new SmartReservationSearchUnavailableException(messages.getMessage("smartSearch.unavailable")));

        return buildResult(original, excludedKeys, referenceData, draft);
    }

    private SmartReservationSearchResult emptyResult(String original) {
        ReservationService.ReservationSearchCriteria criteria = new ReservationService.ReservationSearchCriteria(
                null, null, null, null, null, 1, null, null, List.of(), List.of());
        return new SmartReservationSearchResult(original, criteria, List.of(), false, PROVIDER_NAME, List.of());
    }

    private SearchReferenceData loadReferenceData() {
        List<Warehouse> warehouses = reservationService.reservationWarehouses();
        List<RentalSubcategory> classes = dataManager.load(RentalSubcategory.class)
                .query("select e from RentalSubcategory e where e.active = true order by e.sortOrder, e.name")
                .list();
        List<RentalType> types = dataManager.load(RentalType.class)
                .query("select e from RentalType e where e.active = true order by e.sortOrder, e.name")
                .list();
        List<RentalItemCondition> conditions = dataManager.load(RentalItemCondition.class)
                .query("select e from RentalItemCondition e where e.active = true order by e.sortOrder, e.name")
                .list();
        List<RentalTag> tags = rentalItemService.loadActiveTags();

        Map<UUID, RentalClassifierAttribute> bindings = new LinkedHashMap<>();
        for (RentalClassifierAttribute binding : rentalItemService.loadActiveClassifierAttributes()) {
            RentalAttributeDefinition definition = binding.getAttributeDefinition();
            if (definition == null || definition.getId() == null) {
                continue;
            }
            bindings.putIfAbsent(definition.getId(), binding);
        }

        Map<UUID, List<RentalAttributeOption>> optionsByDefinition = new LinkedHashMap<>();
        for (RentalClassifierAttribute binding : bindings.values()) {
            RentalAttributeDefinition definition = binding.getAttributeDefinition();
            if (definition == null || definition.getId() == null) {
                continue;
            }
            optionsByDefinition.put(definition.getId(), rentalItemService.loadAttributeOptions(definition));
        }

        return new SearchReferenceData(warehouses, classes, types, conditions, tags, bindings, optionsByDefinition);
    }

    private String buildContextJson(SearchReferenceData referenceData) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("warehouses", referenceData.warehouses().stream()
                .map(warehouse -> Map.of(
                        "code", safe(warehouse.getCode()),
                        "name", safe(warehouse.getName()),
                        "city", safe(warehouse.getCity())))
                .toList());
        context.put("classes", referenceData.classes().stream()
                .map(rentalClass -> Map.of(
                        "code", safe(rentalClass.getCode()),
                        "name", safe(rentalClass.getName())))
                .toList());
        context.put("types", referenceData.types().stream()
                .map(type -> Map.of(
                        "code", safe(type.getCode()),
                        "name", safe(type.getName()),
                        "classCode", type.getSubcategory() == null ? "" : safe(type.getSubcategory().getCode())))
                .toList());
        context.put("conditions", referenceData.conditions().stream()
                .map(condition -> Map.of(
                        "code", safe(condition.getCode()),
                        "name", safe(condition.getName())))
                .toList());
        context.put("tags", referenceData.tags().stream()
                .map(tag -> Map.of(
                        "code", safe(tag.getCode()),
                        "name", safe(tag.getName())))
                .toList());
        context.put("attributes", referenceData.bindings().values().stream()
                .map(binding -> {
                    RentalAttributeDefinition definition = binding.getAttributeDefinition();
                    Map<String, Object> attribute = new LinkedHashMap<>();
                    attribute.put("code", definition == null ? "" : safe(definition.getCode()));
                    attribute.put("name", definition == null ? "" : safe(definition.getName()));
                    attribute.put("dataType", definition == null || definition.getDataType() == null ? "" : definition.getDataType().name());
                    List<Map<String, Object>> options = definition == null || definition.getId() == null
                            ? List.of()
                            : referenceData.optionsByDefinition().getOrDefault(definition.getId(), List.of()).stream()
                            .map(option -> {
                                Map<String, Object> optionJson = new LinkedHashMap<>();
                                optionJson.put("code", safe(option.getCode()));
                                optionJson.put("name", safe(option.getName()));
                                return optionJson;
                            })
                            .toList();
                    attribute.put("options", options);
                    return attribute;
                })
                .toList());
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(context);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to build smart search AI context", ex);
        }
    }

    private SmartReservationSearchResult buildResult(String original,
                                                     Collection<String> excludedKeys,
                                                     SearchReferenceData referenceData,
                                                     SmartReservationAiDraft draft) {
        Set<String> excluded = excludedKeys == null
                ? Set.of()
                : excludedKeys.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<SmartReservationRecognizedToken> tokens = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Warehouse warehouse = resolveWarehouse(referenceData.warehouses(), draft.warehouseCode());
        String warehouseQuery = null;
        if (warehouse != null) {
            String tokenKey = tokenKey("warehouse", warehouse.getCode(), warehouse.getName());
            if (!isExcluded(excluded, tokenKey)) {
                tokens.add(new SmartReservationRecognizedToken(tokenKey, SmartReservationTokenType.WAREHOUSE, warehouseLabel(warehouse), true, null));
            } else {
                warehouse = null;
            }
        } else if (draft.warehouseCode() != null) {
            warehouseQuery = draft.warehouseCode();
            warnings.add(MessageFormat.format(messages.getMessage("smartSearch.warning.unresolvedWarehouse"), draft.warehouseCode()));
        }

        RentalSubcategory rentalClass = resolveClass(referenceData.classes(), draft.classCode(), draft.subcategoryCode(), draft.categoryCode());
        if (rentalClass != null) {
            String key = tokenKey("class", rentalClass.getCode(), rentalClass.getName());
            if (!isExcluded(excluded, key)) {
                tokens.add(new SmartReservationRecognizedToken(key, SmartReservationTokenType.CLASS, rentalClass.getName(), true, null));
            } else {
                rentalClass = null;
            }
        } else if (draft.classCode() != null || draft.subcategoryCode() != null || draft.categoryCode() != null) {
            String unresolved = draft.classCode() != null
                    ? draft.classCode()
                    : draft.subcategoryCode() != null
                    ? draft.subcategoryCode()
                    : draft.categoryCode();
            warnings.add(MessageFormat.format(messages.getMessage("smartSearch.warning.unresolvedClass"), unresolved));
        }

        RentalType type = resolveType(referenceData.types(), draft.typeCode());
        if (type != null) {
            String key = tokenKey("type", type.getCode(), type.getName());
            if (!isExcluded(excluded, key)) {
                tokens.add(new SmartReservationRecognizedToken(key, SmartReservationTokenType.TYPE, type.getName(), true, null));
            } else {
                type = null;
            }
        } else if (draft.typeCode() != null) {
            warnings.add(MessageFormat.format(messages.getMessage("smartSearch.warning.unresolvedType"), draft.typeCode()));
        }

        RentalItemCondition condition = resolveCondition(referenceData.conditions(), draft.conditionCode());
        if (condition != null) {
            String key = tokenKey("condition", condition.getCode(), condition.getName());
            if (!isExcluded(excluded, key)) {
                tokens.add(new SmartReservationRecognizedToken(key, SmartReservationTokenType.CONDITION, condition.getName(), true, null));
            } else {
                condition = null;
            }
        } else if (draft.conditionCode() != null) {
            warnings.add(MessageFormat.format(messages.getMessage("smartSearch.warning.unresolvedCondition"), draft.conditionCode()));
        }

        Integer quantity = draft.quantity() != null && draft.quantity() > 0 ? draft.quantity() : 1;
        if (draft.quantity() != null && draft.quantity() > 0 && !isExcluded(excluded, "quantity")) {
            addToken(tokens, excluded, "quantity", "quantity", String.valueOf(quantity), SmartReservationTokenType.QUANTITY, MessageFormat.format(messages.getMessage("smartSearch.token.quantity"), quantity), true, null);
        } else if (isExcluded(excluded, "quantity")) {
            quantity = 1;
        }

        List<SmartReservationAttributeCriterion> attributeCriteria = resolveAttributes(referenceData, draft, excluded, warnings, tokens);
        List<RentalTag> tags = resolveTags(referenceData.tags(), draft.tagCodes(), excluded, warnings, tokens);

        String freeText = draft.freeText();
        ReservationService.ReservationSearchCriteria criteria = new ReservationService.ReservationSearchCriteria(
                warehouse,
                warehouseQuery,
                null,
                rentalClass,
                type,
                quantity,
                freeText,
                condition,
                attributeCriteria,
                tags);

        List<String> mergedWarnings = new ArrayList<>(warnings);
        if (draft.warnings() != null) {
            mergedWarnings.addAll(draft.warnings().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .toList());
        }
        return new SmartReservationSearchResult(original, criteria, tokens, true, PROVIDER_NAME, mergedWarnings);
    }

    private List<SmartReservationAttributeCriterion> resolveAttributes(SearchReferenceData referenceData,
                                                                       SmartReservationAiDraft draft,
                                                                       Set<String> excluded,
                                                                       List<String> warnings,
                                                                       List<SmartReservationRecognizedToken> tokens) {
        if (draft.attributeCriteria() == null || draft.attributeCriteria().isEmpty()) {
            return List.of();
        }
        List<SmartReservationAttributeCriterion> resolved = new ArrayList<>();
        for (SmartReservationAiAttributeDraft aiAttribute : draft.attributeCriteria()) {
            if (aiAttribute == null) {
                continue;
            }
            RentalAttributeDefinition definition = resolveAttributeDefinition(referenceData, aiAttribute.code());
            if (definition == null) {
                if (aiAttribute.code() != null) {
                    warnings.add(MessageFormat.format(messages.getMessage("smartSearch.warning.unresolvedAttribute"), aiAttribute.code()));
                }
                continue;
            }
            String attributeKey = tokenKey("attribute", definition.getCode(), definition.getName());
            if (isExcluded(excluded, attributeKey)) {
                continue;
            }
            List<RentalAttributeOption> options = resolveOptions(referenceData, definition, aiAttribute.options(), warnings);
            SmartReservationAttributeCriterion criterion = new SmartReservationAttributeCriterion(
                    attributeKey,
                    definition,
                    options,
                    aiAttribute.numberValue(),
                    aiAttribute.booleanValue(),
                    aiAttribute.textValue());
            if (criterion.isEmpty()) {
                continue;
            }
            resolved.add(criterion);
            tokens.add(new SmartReservationRecognizedToken(attributeKey, SmartReservationTokenType.ATTRIBUTE, criterion.label(), true, null));
        }
        return resolved;
    }

    private List<RentalTag> resolveTags(List<RentalTag> availableTags,
                                        List<String> tagCodes,
                                        Set<String> excluded,
                                        List<String> warnings,
                                        List<SmartReservationRecognizedToken> tokens) {
        if (tagCodes == null || tagCodes.isEmpty()) {
            return List.of();
        }
        List<RentalTag> resolved = new ArrayList<>();
        for (String tagCode : tagCodes) {
            if (tagCode == null || tagCode.isBlank()) {
                continue;
            }
            RentalTag tag = availableTags.stream()
                    .filter(candidate -> matchesCodeOrName(candidate.getCode(), candidate.getName(), tagCode))
                    .findFirst()
                    .orElse(null);
            if (tag == null) {
                warnings.add(MessageFormat.format(messages.getMessage("smartSearch.warning.unresolvedTag"), tagCode));
                continue;
            }
            String key = tokenKey("tag", tag.getCode(), tag.getName());
            if (isExcluded(excluded, key)) {
                continue;
            }
            resolved.add(tag);
            tokens.add(new SmartReservationRecognizedToken(key, SmartReservationTokenType.TAG, tag.getName(), true, null));
        }
        return resolved;
    }

    private List<RentalAttributeOption> resolveOptions(SearchReferenceData referenceData,
                                                       RentalAttributeDefinition definition,
                                                       List<String> optionCodes,
                                                       List<String> warnings) {
        if (definition == null || definition.getId() == null || optionCodes == null || optionCodes.isEmpty()) {
            return List.of();
        }
        List<RentalAttributeOption> available = referenceData.optionsByDefinition().getOrDefault(definition.getId(), List.of());
        List<RentalAttributeOption> resolved = new ArrayList<>();
        for (String optionCode : optionCodes) {
            if (optionCode == null || optionCode.isBlank()) {
                continue;
            }
            RentalAttributeOption option = available.stream()
                    .filter(candidate -> matchesCodeOrName(candidate.getCode(), candidate.getName(), optionCode))
                    .findFirst()
                    .orElse(null);
            if (option == null) {
                warnings.add(MessageFormat.format(messages.getMessage("smartSearch.warning.unresolvedAttributeOption"), optionCode));
                continue;
            }
            resolved.add(option);
        }
        return resolved;
    }

    private RentalAttributeDefinition resolveAttributeDefinition(SearchReferenceData referenceData, String codeOrName) {
        if (codeOrName == null || codeOrName.isBlank()) {
            return null;
        }
        return referenceData.bindings().values().stream()
                .map(RentalClassifierAttribute::getAttributeDefinition)
                .filter(Objects::nonNull)
                .filter(definition -> matchesCodeOrName(definition.getCode(), definition.getName(), codeOrName))
                .findFirst()
                .orElse(null);
    }

    private Warehouse resolveWarehouse(List<Warehouse> warehouses, String codeOrName) {
        if (codeOrName == null || codeOrName.isBlank()) {
            return null;
        }
        return warehouses.stream()
                .filter(warehouse -> matchesCodeOrName(warehouse.getCode(), warehouse.getName(), codeOrName)
                        || matchesCodeOrName(warehouse.getCity(), null, codeOrName))
                .findFirst()
                .orElse(null);
    }

    private RentalSubcategory resolveClass(List<RentalSubcategory> classes, String... codeOrNames) {
        if (classes == null || classes.isEmpty() || codeOrNames == null || codeOrNames.length == 0) {
            return null;
        }
        return classes.stream()
                .filter(rentalClass -> {
                    for (String codeOrName : codeOrNames) {
                        if (matchesCodeOrName(rentalClass.getCode(), rentalClass.getName(), codeOrName)) {
                            return true;
                        }
                    }
                    return false;
                })
                .findFirst()
                .orElse(null);
    }

    private RentalType resolveType(List<RentalType> types, String codeOrName) {
        return types.stream()
                .filter(type -> matchesCodeOrName(type.getCode(), type.getName(), codeOrName))
                .findFirst()
                .orElse(null);
    }

    private RentalItemCondition resolveCondition(List<RentalItemCondition> conditions, String codeOrName) {
        return conditions.stream()
                .filter(condition -> matchesCodeOrName(condition.getCode(), condition.getName(), codeOrName))
                .findFirst()
                .orElse(null);
    }

    private void addToken(List<SmartReservationRecognizedToken> tokens,
                          Set<String> excluded,
                          String prefix,
                          String code,
                          String name,
                          SmartReservationTokenType type,
                          String label,
                          boolean removable,
                          String note) {
        String key = tokenKey(prefix, code, name);
        if (isExcluded(excluded, key)) {
            return;
        }
        tokens.add(new SmartReservationRecognizedToken(key, type, label, removable, note));
    }

    private boolean isExcluded(Set<String> excluded, String key) {
        return key != null && excluded.contains(key.trim().toLowerCase(Locale.ROOT));
    }

    private boolean matchesCodeOrName(String code, String name, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        String normalizedCandidate = normalize(candidate);
        return matchesValue(code, normalizedCandidate) || matchesValue(name, normalizedCandidate);
    }

    private boolean matchesValue(String value, String normalizedCandidate) {
        if (value == null || normalizedCandidate == null || normalizedCandidate.isBlank()) {
            return false;
        }
        String normalizedValue = normalize(value);
        return normalizedValue.equals(normalizedCandidate) || normalizedValue.contains(normalizedCandidate) || normalizedCandidate.contains(normalizedValue);
    }

    private String buildTokenLabel(String primary, String secondary) {
        if (primary == null || primary.isBlank()) {
            return secondary == null ? "" : secondary;
        }
        if (secondary == null || secondary.isBlank() || primary.equalsIgnoreCase(secondary)) {
            return primary;
        }
        return primary + " - " + secondary;
    }

    private String tokenKey(String prefix, String code, String name) {
        String base = firstNonBlank(code, name, prefix);
        return (prefix + ":" + normalize(base)).toLowerCase(Locale.ROOT);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String warehouseLabel(Warehouse warehouse) {
        if (warehouse == null) {
            return "";
        }
        return buildTokenLabel(
                firstNonBlank(warehouse.getCode(), warehouse.getName()),
                warehouse.getCity());
    }

    private record SearchReferenceData(
            List<Warehouse> warehouses,
            List<RentalSubcategory> classes,
            List<RentalType> types,
            List<RentalItemCondition> conditions,
            List<RentalTag> tags,
            Map<UUID, RentalClassifierAttribute> bindings,
            Map<UUID, List<RentalAttributeOption>> optionsByDefinition) {
    }
}
