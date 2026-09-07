package dev.buhanzaz.rwms.asset.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.asset.domain.CabinCatalogItem;
import dev.buhanzaz.rwms.asset.domain.CabinCatalogKind;
import dev.buhanzaz.rwms.asset.mapper.CabinCatalogItemMapper;
import dev.buhanzaz.rwms.asset.repository.CabinCatalogItemRepository;
import dev.buhanzaz.rwms.asset.repository.CabinTypeDimensionRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemCharacteristicRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import tools.jackson.databind.ObjectMapper;

class CabinCompositionServiceDeleteTest {
  private final CabinCatalogItemRepository catalog = mock(CabinCatalogItemRepository.class);
  private final CabinTypeDimensionRepository typeDimensions = mock(CabinTypeDimensionRepository.class);
  private final RentalItemRepository rentalItems = mock(RentalItemRepository.class);
  private final RentalItemCharacteristicRepository rentalItemCharacteristics =
      mock(RentalItemCharacteristicRepository.class);
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final CabinCompositionService service =
      new CabinCompositionService(
          catalog,
          typeDimensions,
          rentalItems,
          rentalItemCharacteristics,
          mock(CabinCatalogItemMapper.class),
          mock(AssetIdempotencyStore.class),
          new ObjectMapper(),
          jdbc);

  @Test
  void deletesAnUnreferencedType() {
    UUID id = UUID.randomUUID();
    CabinCatalogItem item = catalogItem(id, CabinCatalogKind.TYPE, "Прорабская", 3);

    service.deleteCatalogItem(id, 3);

    verify(typeDimensions).existsByCabinTypeId(id);
    verify(rentalItems).existsByRentalTypeId(id);
    verify(catalog).delete(item);
    verify(catalog).flush();
  }

  @Test
  void locksTheKindBeforeLoadingAndDeletingTheCatalogItem() {
    UUID id = UUID.randomUUID();
    CabinCatalogItem item = catalogItem(id, CabinCatalogKind.TYPE, "Прорабская", 3);

    service.deleteCatalogItem(id, 3);

    org.mockito.InOrder calls = org.mockito.Mockito.inOrder(catalog, jdbc);
    calls.verify(catalog).findKindById(id);
    calls
        .verify(jdbc)
        .query(
            org.mockito.ArgumentMatchers.eq(
                "select pg_advisory_xact_lock(hashtextextended(?, 0))"),
            org.mockito.ArgumentMatchers.any(RowCallbackHandler.class),
            org.mockito.ArgumentMatchers.eq("cabin-catalog:TYPE"));
    calls.verify(catalog).findById(id);
    calls.verify(catalog).delete(item);
  }

  @Test
  void refusesToDeleteTypeWhileItHasDimensionLinks() {
    UUID id = UUID.randomUUID();
    CabinCatalogItem item = catalogItem(id, CabinCatalogKind.TYPE, "Прорабская", 0);
    when(typeDimensions.existsByCabinTypeId(id)).thenReturn(true);

    assertThatThrownBy(() -> service.deleteCatalogItem(id, 0))
        .isInstanceOf(AssetConflictException.class)
        .hasMessage("Нельзя удалить «Прорабская»: сначала отвяжите габариты от типа бытовки.");

    verify(catalog, never()).delete(item);
  }

  @Test
  void refusesToDeleteDimensionWhileItIsLinkedToAType() {
    UUID id = UUID.randomUUID();
    CabinCatalogItem item = catalogItem(id, CabinCatalogKind.DIMENSION, "2.4 × 6", 0);
    when(typeDimensions.existsByDimensionId(id)).thenReturn(true);

    assertThatThrownBy(() -> service.deleteCatalogItem(id, 0))
        .isInstanceOf(AssetConflictException.class)
        .hasMessage("Нельзя удалить «2.4 × 6»: сначала отвяжите габарит от типов бытовок.");

    verify(catalog, never()).delete(item);
  }

  @Test
  void refusesToDeleteFinishingUsedByAnExistingCabin() {
    UUID id = UUID.randomUUID();
    CabinCatalogItem item = catalogItem(id, CabinCatalogKind.FINISHING, "ПВХ", 0);
    when(rentalItems.existsByFinishingId(id)).thenReturn(true);

    assertThatThrownBy(() -> service.deleteCatalogItem(id, 0))
        .isInstanceOf(AssetConflictException.class)
        .hasMessage("Нельзя удалить «ПВХ»: отделка уже выбрана у существующих бытовок.");

    verify(catalog, never()).delete(item);
  }

  @Test
  void refusesToDeleteCharacteristicUsedByAnExistingCabin() {
    UUID id = UUID.randomUUID();
    CabinCatalogItem item = catalogItem(id, CabinCatalogKind.CHARACTERISTIC, "Две лампы", 0);
    when(rentalItemCharacteristics.existsByCharacteristicId(id)).thenReturn(true);

    assertThatThrownBy(() -> service.deleteCatalogItem(id, 0))
        .isInstanceOf(AssetConflictException.class)
        .hasMessage(
            "Нельзя удалить «Две лампы»: характеристика уже выбрана у существующих бытовок.");

    verify(catalog, never()).delete(item);
  }

  @Test
  void rejectsStaleDeleteBeforeCheckingReferences() {
    UUID id = UUID.randomUUID();
    catalogItem(id, CabinCatalogKind.FINISHING, "ПВХ", 5);

    assertThatThrownBy(() -> service.deleteCatalogItem(id, 4))
        .isInstanceOf(AssetConflictException.class)
        .hasMessage("Cabin setting changed concurrently");

    verify(rentalItems, never()).existsByFinishingId(id);
    verify(catalog, never()).delete(org.mockito.ArgumentMatchers.any());
  }

  private CabinCatalogItem catalogItem(UUID id, CabinCatalogKind kind, String name, long version) {
    CabinCatalogItem item = mock(CabinCatalogItem.class);
    when(item.getId()).thenReturn(id);
    when(item.getKind()).thenReturn(kind);
    when(item.getName()).thenReturn(name);
    when(item.getVersion()).thenReturn(version);
    when(catalog.findKindById(id)).thenReturn(Optional.of(kind));
    when(catalog.findById(id)).thenReturn(Optional.of(item));
    return item;
  }
}
