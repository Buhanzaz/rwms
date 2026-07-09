package dev.buhanzaz.wmspanel.service.smartsearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.buhanzaz.wmspanel.entity.RentalAttributeDataType;
import dev.buhanzaz.wmspanel.entity.RentalAttributeDefinition;
import dev.buhanzaz.wmspanel.entity.RentalAttributeOption;
import dev.buhanzaz.wmspanel.entity.RentalCategory;
import dev.buhanzaz.wmspanel.entity.RentalClassifierAttribute;
import dev.buhanzaz.wmspanel.entity.RentalItemCondition;
import dev.buhanzaz.wmspanel.entity.RentalSubcategory;
import dev.buhanzaz.wmspanel.entity.RentalTag;
import dev.buhanzaz.wmspanel.entity.RentalType;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.service.RentalItemService;
import dev.buhanzaz.wmspanel.service.ReservationService;
import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SmartReservationSearchServiceTest {

    private final DataManager dataManager = mock(DataManager.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
    private final ReservationService reservationService = mock(ReservationService.class);
    private final RentalItemService rentalItemService = mock(RentalItemService.class);
    private final SmartReservationAiParser aiParser = mock(SmartReservationAiParser.class);
    private final SmartReservationAiProperties aiProperties = mock(SmartReservationAiProperties.class);
    private final Messages messages = mock(Messages.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private SmartReservationSearchService service;

    private Warehouse ekb;
    private Warehouse spb;
    private Warehouse moscow;
    private RentalSubcategory cabins;
    private RentalCategory cabinsCategory;
    private RentalCategory containers;
    private RentalSubcategory sanblock;
    private RentalType bk2;
    private RentalType container20;
    private RentalItemCondition good;
    private RentalAttributeDefinition finishing;
    private RentalAttributeDefinition linoleum;
    private RentalAttributeDefinition showers;
    private RentalAttributeDefinition toilets;
    private RentalAttributeDefinition boiler;
    private RentalAttributeOption dvp;
    private RentalAttributeOption osb;
    private RentalAttributeOption linoleumNew;

    @BeforeEach
    void setUp() {
        service = new SmartReservationSearchService(
                reservationService,
                dataManager,
                rentalItemService,
                aiParser,
                aiProperties,
                messages,
                objectMapper);

        when(aiProperties.isConfigured()).thenReturn(true);

        ekb = warehouse("EKB", "Екатеринбург", "Екатеринбург");
        spb = warehouse("SPB", "Санкт-Петербург", "Санкт-Петербург");
        moscow = warehouse("MSK", "Москва", "Москва");
        when(reservationService.reservationWarehouses()).thenReturn(List.of(ekb, spb, moscow));

        cabinsCategory = category("CABINS", "Бытовка");
        containers = category("CONTAINERS", "Контейнер");
        when(dataManager.load(RentalCategory.class).query(anyString()).list()).thenReturn(List.of(cabinsCategory, containers));

        cabins = subcategory("CABINS", "Бытовка", cabinsCategory);
        RentalSubcategory ordinaryBk = subcategory("ORDINARY_BK", "Обычная БК", cabinsCategory);
        RentalSubcategory module = subcategory("MODULE", "Модульное здание", cabinsCategory);
        RentalSubcategory security = subcategory("SECURITY", "Пост охраны", cabinsCategory);
        sanblock = subcategory("SANBLOCK", "Санблок", cabinsCategory);
        when(dataManager.load(RentalSubcategory.class).query(anyString()).list()).thenReturn(List.of(cabins, ordinaryBk, module, security, sanblock));

        bk2 = type("BK2", "БК2", ordinaryBk);
        container20 = type("CONTAINER_20", "20 футов", null);
        when(dataManager.load(RentalType.class).query(anyString()).list()).thenReturn(List.of(bk2, container20));

        good = condition("GOOD", "Хорошее");
        when(dataManager.load(RentalItemCondition.class).query(anyString()).list()).thenReturn(List.of(good));

        finishing = attribute("FINISHING", "Отделка", RentalAttributeDataType.ENUM);
        linoleum = attribute("LINOLEUM", "Линолеум", RentalAttributeDataType.ENUM);
        showers = attribute("SHOWERS", "Душевые", RentalAttributeDataType.NUMBER);
        toilets = attribute("TOILETS", "Туалеты", RentalAttributeDataType.NUMBER);
        boiler = attribute("BOILER", "Бойлер", RentalAttributeDataType.BOOLEAN);

        dvp = option(finishing, "DVP", "ДВП");
        osb = option(finishing, "OSB", "ОСБ");
        linoleumNew = option(linoleum, "NEW", "Новый");

        RentalClassifierAttribute finishingBinding = binding(finishing);
        RentalClassifierAttribute linoleumBinding = binding(linoleum);
        RentalClassifierAttribute showersBinding = binding(showers);
        RentalClassifierAttribute toiletsBinding = binding(toilets);
        RentalClassifierAttribute boilerBinding = binding(boiler);
        when(rentalItemService.loadActiveClassifierAttributes()).thenReturn(List.of(
                finishingBinding,
                linoleumBinding,
                showersBinding,
                toiletsBinding,
                boilerBinding));
        when(rentalItemService.loadAttributeOptions(finishing)).thenReturn(List.of(dvp, osb));
        when(rentalItemService.loadAttributeOptions(linoleum)).thenReturn(List.of(linoleumNew));
        when(rentalItemService.loadAttributeOptions(showers)).thenReturn(List.of());
        when(rentalItemService.loadAttributeOptions(toilets)).thenReturn(List.of());
        when(rentalItemService.loadAttributeOptions(boiler)).thenReturn(List.of());
        when(rentalItemService.loadActiveTags()).thenReturn(List.of(tag("TAG_A", "Тег A")));
        when(messages.getMessage("smartSearch.token.quantity")).thenReturn("Quantity: {0}");
    }

    @Test
    void mapsAiDraftIntoReservationCriteria() {
        when(aiParser.parse(anyString(), anyString())).thenReturn(Optional.of(new SmartReservationAiDraft(
                "EKB",
                "CABINS",
                null,
                null,
                "GOOD",
                null,
                null,
                List.of(new SmartReservationAiAttributeDraft("FINISHING", List.of("DVP", "OSB"), null, null, null)),
                List.of("TAG_A"),
                List.of())));

        SmartReservationSearchResult result = service.parse("Нужны бытовки из Екатеринбурга, ДВП, ОСБ в хорошем состоянии.");

        assertThat(result.originalText()).isEqualTo("Нужны бытовки из Екатеринбурга, ДВП, ОСБ в хорошем состоянии.");
        assertThat(result.aiUsed()).isTrue();
        assertThat(result.providerName()).isEqualTo("MISTRAL_OPENAI_COMPAT");
        assertThat(result.criteria().warehouse()).isSameAs(ekb);
        assertThat(result.criteria().rentalClass()).isSameAs(cabins);
        assertThat(result.criteria().condition()).isSameAs(good);
        assertThat(result.criteria().attributeCriteria()).hasSize(1);
        assertThat(result.criteria().attributeCriteria().get(0).definition()).isSameAs(finishing);
        assertThat(result.criteria().attributeCriteria().get(0).options()).containsExactlyInAnyOrder(dvp, osb);
        assertThat(result.criteria().tags()).hasSize(1);
        assertThat(result.criteria().tags().get(0).getCode()).isEqualTo("TAG_A");
    }

    @Test
    void mapsQuantityAndTypeFromAiDraft() {
        when(aiParser.parse(anyString(), anyString())).thenReturn(Optional.of(new SmartReservationAiDraft(
                "SPB",
                null,
                null,
                "BK2",
                null,
                3,
                null,
                List.of(new SmartReservationAiAttributeDraft("LINOLEUM", List.of("NEW"), null, null, null)),
                List.of(),
                List.of())));

        SmartReservationSearchResult result = service.parse("Нужно 3 БК2 в СПБ с новым линолеумом.");

        assertThat(result.criteria().warehouse()).isSameAs(spb);
        assertThat(result.criteria().quantity()).isEqualTo(3);
        assertThat(result.criteria().type()).isSameAs(bk2);
        assertThat(result.criteria().attributeCriteria()).hasSize(1);
        assertThat(result.criteria().attributeCriteria().get(0).definition()).isSameAs(linoleum);
        assertThat(result.criteria().attributeCriteria().get(0).options()).containsExactly(linoleumNew);
    }

    @Test
    void keepsWarningsFromAiDraftForAccessoryIntent() {
        when(aiParser.parse(anyString(), anyString())).thenReturn(Optional.of(new SmartReservationAiDraft(
                "EKB",
                "CABINS",
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of("Требуется отдельная проработка аксессуаров: стулья, конвектор"))));

        SmartReservationSearchResult result = service.parse("Нужны бытовки в ЕКБ со стульями и конвектором.");

        assertThat(result.criteria().warehouse()).isSameAs(ekb);
        assertThat(result.criteria().rentalClass()).isSameAs(cabins);
        assertThat(result.warnings()).anyMatch(value -> value.contains("стулья"));
        assertThat(result.warnings()).anyMatch(value -> value.contains("конвектор"));
    }

    @Test
    void failsFastWhenAiIsNotConfigured() {
        when(aiProperties.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.parse("Контейнер 20 футов в Москве."))
                .isInstanceOf(SmartReservationSearchUnavailableException.class);
    }

    private Warehouse warehouse(String code, String name, String city) {
        Warehouse warehouse = new Warehouse();
        warehouse.setId(UUID.randomUUID());
        warehouse.setCode(code);
        warehouse.setName(name);
        warehouse.setCity(city);
        warehouse.setActive(true);
        return warehouse;
    }

    private RentalCategory category(String code, String name) {
        RentalCategory category = new RentalCategory();
        category.setId(UUID.randomUUID());
        category.setCode(code);
        category.setName(name);
        category.setActive(true);
        return category;
    }

    private RentalSubcategory subcategory(String code, String name, RentalCategory category) {
        RentalSubcategory subcategory = new RentalSubcategory();
        subcategory.setId(UUID.randomUUID());
        subcategory.setCode(code);
        subcategory.setName(name);
        subcategory.setCategory(category);
        subcategory.setActive(true);
        return subcategory;
    }

    private RentalType type(String code, String name, RentalSubcategory subcategory) {
        RentalType type = new RentalType();
        type.setId(UUID.randomUUID());
        type.setCode(code);
        type.setName(name);
        type.setSubcategory(subcategory);
        type.setActive(true);
        return type;
    }

    private RentalItemCondition condition(String code, String name) {
        RentalItemCondition condition = new RentalItemCondition();
        condition.setId(UUID.randomUUID());
        condition.setCode(code);
        condition.setName(name);
        condition.setActive(true);
        return condition;
    }

    private RentalAttributeDefinition attribute(String code, String name, RentalAttributeDataType dataType) {
        RentalAttributeDefinition definition = new RentalAttributeDefinition();
        definition.setId(UUID.randomUUID());
        definition.setCode(code);
        definition.setName(name);
        definition.setDataType(dataType);
        definition.setActive(true);
        return definition;
    }

    private RentalAttributeOption option(RentalAttributeDefinition definition, String code, String name) {
        RentalAttributeOption option = new RentalAttributeOption();
        option.setId(UUID.randomUUID());
        option.setAttributeDefinition(definition);
        option.setCode(code);
        option.setName(name);
        option.setActive(true);
        return option;
    }

    private RentalClassifierAttribute binding(RentalAttributeDefinition definition) {
        RentalClassifierAttribute binding = new RentalClassifierAttribute();
        binding.setId(UUID.randomUUID());
        binding.setAttributeDefinition(definition);
        binding.setActive(true);
        return binding;
    }

    private RentalTag tag(String code, String name) {
        RentalTag tag = new RentalTag();
        tag.setId(UUID.randomUUID());
        tag.setCode(code);
        tag.setName(name);
        tag.setActive(true);
        return tag;
    }
}
