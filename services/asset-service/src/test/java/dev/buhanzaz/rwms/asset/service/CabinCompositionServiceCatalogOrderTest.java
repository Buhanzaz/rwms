package dev.buhanzaz.rwms.asset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.CabinCatalogOrderItemRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CabinCatalogValueResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CabinTypeDimensionResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateCabinCatalogItemRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.ReplaceCabinCatalogOrderRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.UpdateCabinCatalogItemRequest;
import dev.buhanzaz.rwms.asset.domain.CabinCatalogItem;
import dev.buhanzaz.rwms.asset.domain.CabinCatalogKind;
import dev.buhanzaz.rwms.asset.domain.CabinTypeDimension;
import dev.buhanzaz.rwms.asset.mapper.CabinCatalogItemMapper;
import dev.buhanzaz.rwms.asset.repository.CabinCatalogItemRepository;
import dev.buhanzaz.rwms.asset.repository.CabinTypeDimensionRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemCharacteristicRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import tools.jackson.databind.ObjectMapper;

class CabinCompositionServiceCatalogOrderTest {
  private final CabinCatalogItemRepository catalog = mock(CabinCatalogItemRepository.class);
  private final CabinTypeDimensionRepository typeDimensions = mock(CabinTypeDimensionRepository.class);
  private final RentalItemRepository rentalItems = mock(RentalItemRepository.class);
  private final RentalItemCharacteristicRepository rentalItemCharacteristics =
      mock(RentalItemCharacteristicRepository.class);
  private final CabinCatalogItemMapper mapper = mock(CabinCatalogItemMapper.class);
  private final AssetIdempotencyStore idempotency = mock(AssetIdempotencyStore.class);
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final CabinCompositionService service =
      new CabinCompositionService(
          catalog,
          typeDimensions,
          rentalItems,
          rentalItemCharacteristics,
          mapper,
          idempotency,
          new ObjectMapper(),
          jdbc);

  @Test
  void replacesTheExactKindOrderAfterFencingEverySubmittedItem() {
    CabinCatalogItem first = catalogItem(CabinCatalogKind.TYPE, "Первый", 2, 0);
    CabinCatalogItem second = catalogItem(CabinCatalogKind.TYPE, "Второй", 7, 1);
    stubSettings(CabinCatalogKind.TYPE, List.of(first, second));

    service.replaceCatalogOrder(
        CabinCatalogKind.TYPE,
        new ReplaceCabinCatalogOrderRequest(
            List.of(
                new CabinCatalogOrderItemRequest(second.getId(), 7L),
                new CabinCatalogOrderItemRequest(first.getId(), 2L))));

    verify(second).reorder(0);
    verify(first).reorder(1);
    verify(catalog).saveAllAndFlush(List.of(second, first));
  }

  @Test
  void rejectsAnIncompleteOrderBeforeChangingAnyItem() {
    CabinCatalogItem first = catalogItem(CabinCatalogKind.TYPE, "Первый", 2, 0);
    CabinCatalogItem second = catalogItem(CabinCatalogKind.TYPE, "Второй", 7, 1);
    when(catalog.findAllByKindOrderBySortOrderAscIdAsc(CabinCatalogKind.TYPE))
        .thenReturn(List.of(first, second));

    assertThatThrownBy(
            () ->
                service.replaceCatalogOrder(
                    CabinCatalogKind.TYPE,
                    new ReplaceCabinCatalogOrderRequest(
                        List.of(new CabinCatalogOrderItemRequest(first.getId(), 2L)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("every item");

    verify(first, never()).reorder(anyInt());
    verify(second, never()).reorder(anyInt());
    verify(catalog, never()).saveAllAndFlush(any());
  }

  @Test
  void rejectsAStaleItemBeforeChangingTheOrder() {
    CabinCatalogItem first = catalogItem(CabinCatalogKind.TYPE, "Первый", 2, 0);
    CabinCatalogItem second = catalogItem(CabinCatalogKind.TYPE, "Второй", 7, 1);
    when(catalog.findAllByKindOrderBySortOrderAscIdAsc(CabinCatalogKind.TYPE))
        .thenReturn(List.of(first, second));

    assertThatThrownBy(
            () ->
                service.replaceCatalogOrder(
                    CabinCatalogKind.TYPE,
                    new ReplaceCabinCatalogOrderRequest(
                        List.of(
                            new CabinCatalogOrderItemRequest(second.getId(), 6L),
                            new CabinCatalogOrderItemRequest(first.getId(), 2L)))))
        .isInstanceOf(AssetConflictException.class)
        .hasMessage("Cabin setting changed concurrently");

    verify(first, never()).reorder(anyInt());
    verify(second, never()).reorder(anyInt());
    verify(catalog, never()).saveAllAndFlush(any());
  }

  @Test
  void rejectsCustomerVisibilityForANonCharacteristic() {
    CabinCatalogItem type = catalogItem(CabinCatalogKind.TYPE, "Тип", 3, 0);
    when(catalog.findById(type.getId())).thenReturn(Optional.of(type));

    assertThatThrownBy(
            () ->
                service.updateCatalogItem(
                    type.getId(),
                    new UpdateCabinCatalogItemRequest(3L, "Тип", true, false)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("only be changed for cabin characteristics");

    verify(catalog, never()).saveAndFlush(any());
  }

  @Test
  void assignsNewItemsAfterTheCurrentLastPosition() {
    CabinCatalogItem last = catalogItem(CabinCatalogKind.FINISHING, "Последний", 0, 8);
    when(idempotency.replay(any(), eq("cabin-catalog.create"), any(), any()))
        .thenReturn(Optional.empty());
    when(catalog.findFirstByKindOrderBySortOrderDescIdDesc(CabinCatalogKind.FINISHING))
        .thenReturn(Optional.of(last));
    when(catalog.saveAndFlush(any(CabinCatalogItem.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.createCatalogItem(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new CreateCabinCatalogItemRequest(CabinCatalogKind.FINISHING, "Новая отделка"));

    org.mockito.ArgumentCaptor<CabinCatalogItem> captured =
        org.mockito.ArgumentCaptor.forClass(CabinCatalogItem.class);
    verify(catalog).saveAndFlush(captured.capture());
    assertThat(captured.getValue().getSortOrder()).isEqualTo(9);
    assertThat(captured.getValue().isCustomerVisible()).isTrue();
  }

  @Test
  void locksTheCatalogKindBeforeReadingTheOrderToReplace() {
    CabinCatalogItem item = catalogItem(CabinCatalogKind.TYPE, "Первый", 2, 0);
    stubSettings(CabinCatalogKind.TYPE, List.of(item));

    service.replaceCatalogOrder(
        CabinCatalogKind.TYPE,
        new ReplaceCabinCatalogOrderRequest(
            List.of(new CabinCatalogOrderItemRequest(item.getId(), 2L))));

    org.mockito.InOrder calls = org.mockito.Mockito.inOrder(jdbc, catalog);
    calls
        .verify(jdbc)
        .query(
            eq("select pg_advisory_xact_lock(hashtextextended(?, 0))"),
            any(RowCallbackHandler.class),
            eq("cabin-catalog:TYPE"));
    calls.verify(catalog).findAllByKindOrderBySortOrderAscIdAsc(CabinCatalogKind.TYPE);
  }

  @Test
  void usesGlobalDimensionPositionsForCreationOptionRelations() {
    CabinCatalogItem type = catalogItem(CabinCatalogKind.TYPE, "Тип", 0, 0);
    CabinCatalogItem firstDimension = catalogItem(CabinCatalogKind.DIMENSION, "Первый", 0, 0);
    CabinCatalogItem secondDimension = catalogItem(CabinCatalogKind.DIMENSION, "Второй", 0, 1);
    for (CabinCatalogKind kind : CabinCatalogKind.values()) {
      when(catalog.findAllByKindAndActiveTrueOrderBySortOrderAscIdAsc(kind))
          .thenReturn(
              switch (kind) {
                case TYPE -> List.of(type);
                case DIMENSION -> List.of(firstDimension, secondDimension);
                default -> List.of();
              });
    }
    UUID typeId = type.getId();
    UUID firstDimensionId = firstDimension.getId();
    UUID secondDimensionId = secondDimension.getId();
    when(typeDimensions.findAllByCabinTypeIdInOrderByCabinTypeIdAscSortOrderAscIdAsc(any()))
        .thenReturn(
            List.of(
                CabinTypeDimension.create(typeId, secondDimensionId, 0),
                CabinTypeDimension.create(typeId, firstDimensionId, 1)));
    when(mapper.toTypeDimension(any(CabinTypeDimension.class)))
        .thenAnswer(
            invocation -> {
              CabinTypeDimension link = invocation.getArgument(0);
              return new CabinTypeDimensionResponse(
                  link.getCabinTypeId(), link.getDimensionId(), link.getSortOrder());
            });

    assertThat(service.creationOptions().typeDimensions())
        .containsExactly(
            new CabinTypeDimensionResponse(typeId, firstDimensionId, 0),
            new CabinTypeDimensionResponse(typeId, secondDimensionId, 1));
  }

  @Test
  void ordersCompositionCharacteristicsByGlobalCatalogPosition() {
    CabinCatalogItem type = catalogItem(CabinCatalogKind.TYPE, "Тип", 0, 0);
    CabinCatalogItem dimension = catalogItem(CabinCatalogKind.DIMENSION, "Габарит", 0, 0);
    CabinCatalogItem finishing = catalogItem(CabinCatalogKind.FINISHING, "Отделка", 0, 0);
    CabinCatalogItem later = catalogItem(CabinCatalogKind.CHARACTERISTIC, "Позже", 0, 4);
    CabinCatalogItem earlier = catalogItem(CabinCatalogKind.CHARACTERISTIC, "Раньше", 0, 1);
    when(catalog.findAllByIdIn(any()))
        .thenReturn(List.of(type, dimension, finishing, later, earlier));
    when(mapper.toValue(any(CabinCatalogItem.class)))
        .thenAnswer(
            invocation -> {
              CabinCatalogItem item = invocation.getArgument(0);
              return new CabinCatalogValueResponse(item.getId(), item.getName());
            });

    var composition =
        service.compositionFor(
            new CabinCompositionService.CabinSelection(
                type.getId(), dimension.getId(), finishing.getId(), List.of(later.getId(), earlier.getId())));

    assertThat(composition.characteristics())
        .extracting(CabinCatalogValueResponse::name)
        .containsExactly("Раньше", "Позже");
  }

  @Test
  void sortsKnownFacetValuesBeforeLegacyFallbackValues() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    var order = new CabinCompositionService.CatalogOrder(Map.of(first, 0, second, 1));

    assertThat(
            order.orderedFacetNames(
                List.of(
                    new CabinCatalogValueResponse(second, "Второй"),
                    new CabinCatalogValueResponse(null, "Наследуемый"),
                    new CabinCatalogValueResponse(first, "Первый"))))
        .containsExactly("Первый", "Второй", "Наследуемый");
  }

  private void stubSettings(CabinCatalogKind selectedKind, List<CabinCatalogItem> selectedItems) {
    for (CabinCatalogKind kind : CabinCatalogKind.values()) {
      when(catalog.findAllByKindOrderBySortOrderAscIdAsc(kind))
          .thenReturn(kind == selectedKind ? selectedItems : List.of());
    }
  }

  private static CabinCatalogItem catalogItem(
      CabinCatalogKind kind, String name, long version, int sortOrder) {
    CabinCatalogItem item = mock(CabinCatalogItem.class);
    when(item.getId()).thenReturn(UUID.randomUUID());
    when(item.getKind()).thenReturn(kind);
    when(item.getName()).thenReturn(name);
    when(item.getVersion()).thenReturn(version);
    when(item.getSortOrder()).thenReturn(sortOrder);
    when(item.isCustomerVisible()).thenReturn(true);
    return item;
  }
}
