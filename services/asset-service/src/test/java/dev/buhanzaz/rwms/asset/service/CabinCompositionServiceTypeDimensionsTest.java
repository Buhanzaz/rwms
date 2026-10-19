package dev.buhanzaz.rwms.asset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.ReplaceCabinTypeDimensionsRequest;
import dev.buhanzaz.rwms.asset.domain.CabinCatalogItem;
import dev.buhanzaz.rwms.asset.domain.CabinCatalogKind;
import dev.buhanzaz.rwms.asset.domain.CabinTypeDimension;
import dev.buhanzaz.rwms.asset.mapper.CabinCatalogItemMapper;
import dev.buhanzaz.rwms.asset.repository.CabinCatalogItemRepository;
import dev.buhanzaz.rwms.asset.repository.CabinTypeDimensionRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemCharacteristicRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import jakarta.validation.Validation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

class CabinCompositionServiceTypeDimensionsTest {
  private final CabinCatalogItemRepository catalog = mock(CabinCatalogItemRepository.class);
  private final CabinTypeDimensionRepository typeDimensions = mock(CabinTypeDimensionRepository.class);
  private final RentalItemRepository rentalItems = mock(RentalItemRepository.class);
  private final CabinCompositionService service =
      new CabinCompositionService(
          catalog,
          typeDimensions,
          rentalItems,
          mock(RentalItemCharacteristicRepository.class),
          mock(CabinCatalogItemMapper.class),
          mock(AssetIdempotencyStore.class),
          new ObjectMapper(),
          mock(JdbcTemplate.class));

  @Test
  void allowsClearingAllDimensionsWhenTheTypeHasNoCabins() {
    UUID typeId = UUID.randomUUID();
    UUID dimensionId = UUID.randomUUID();
    CabinCatalogItem type = type(typeId, 2);
    when(typeDimensions.findAllByCabinTypeIdOrderBySortOrderAscIdAsc(typeId))
        .thenReturn(List.of(CabinTypeDimension.create(typeId, dimensionId, 0)));
    when(rentalItems.existsByRentalTypeId(typeId)).thenReturn(false);

    service.replaceTypeDimensions(typeId, new ReplaceCabinTypeDimensionsRequest(2L, List.of()));

    verify(typeDimensions).deleteAllByCabinTypeId(typeId);
    verify(typeDimensions).flush();
    verify(typeDimensions).saveAll(List.of());
    verify(catalog).saveAndFlush(type);
  }

  @Test
  void rejectsClearingAllDimensionsWhenTheTypeHasExistingCabins() {
    UUID typeId = UUID.randomUUID();
    UUID dimensionId = UUID.randomUUID();
    type(typeId, 0);
    when(typeDimensions.findAllByCabinTypeIdOrderBySortOrderAscIdAsc(typeId))
        .thenReturn(List.of(CabinTypeDimension.create(typeId, dimensionId, 0)));
    when(rentalItems.existsByRentalTypeId(typeId)).thenReturn(true);

    assertThatThrownBy(
            () ->
                service.replaceTypeDimensions(
                    typeId, new ReplaceCabinTypeDimensionsRequest(0L, List.of())))
        .isInstanceOf(AssetConflictException.class)
        .hasMessage("Нельзя снять все габариты: у типа уже есть существующие бытовки.");

    verify(typeDimensions, never()).deleteAllByCabinTypeId(typeId);
    verify(typeDimensions, never()).flush();
  }

  @Test
  void validatesAnEmptyDimensionsListAsAPresentCommandValue() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var request = new ReplaceCabinTypeDimensionsRequest(0L, List.of());

      assertThat(factory.getValidator().validate(request)).isEmpty();
    }
  }

  private CabinCatalogItem type(UUID id, long version) {
    CabinCatalogItem item = mock(CabinCatalogItem.class);
    when(item.getId()).thenReturn(id);
    when(item.getKind()).thenReturn(CabinCatalogKind.TYPE);
    when(item.getVersion()).thenReturn(version);
    when(catalog.findById(id)).thenReturn(Optional.of(item));
    return item;
  }
}
