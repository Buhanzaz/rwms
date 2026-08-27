package dev.buhanzaz.rwms.logistics.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpLogisticsDependencyGatewayTest {
  private OAuth2AuthorizedClientManager authorizedClients;
  private MockRestServiceServer server;
  private HttpLogisticsDependencyGateway gateway;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    authorizedClients = mock(OAuth2AuthorizedClientManager.class);
    when(authorizedClients.authorize(any()))
        .thenAnswer(
            invocation -> {
              OAuth2AuthorizeRequest request = invocation.getArgument(0);
              String scope =
                  switch (request.getClientRegistrationId()) {
                    case "logistics-asset" -> "asset.logistics";
                    case "logistics-warehouse" -> "warehouse.logistics";
                    case "logistics-warehouse-timezone" -> "warehouse.timezone.read";
                    case "logistics-warehouse-operation" -> "warehouse.operation.mark";
                    case "logistics-warehouse-lifecycle-read" -> "warehouse.lifecycle.read";
                    case "logistics-warehouse-lifecycle-confirm" -> "warehouse.lifecycle.confirm";
                    case "logistics-maintenance" -> "maintenance.logistics";
                    case "logistics-media" -> "media.logistics";
                    case "logistics-task-board" -> "task-board.logistics";
                    default -> throw new IllegalArgumentException("unexpected registration");
                  };
              OAuth2AuthorizedClient authorized = mock(OAuth2AuthorizedClient.class);
              when(authorized.getAccessToken())
                  .thenReturn(
                      new OAuth2AccessToken(
                          OAuth2AccessToken.TokenType.BEARER,
                          "test-" + scope,
                          Instant.now(),
                          Instant.now().plusSeconds(300),
                          Set.of(scope)));
              return authorized;
            });
    gateway =
        new HttpLogisticsDependencyGateway(
            builder.build(),
            authorizedClients,
            new LogisticsDependencyProperties.Validated(
                URI.create("http://auth.test/oauth2/token"),
                "logistics-service",
                "secret",
                URI.create("http://asset.test"),
                URI.create("http://warehouse.test"),
                URI.create("http://task-board.test"),
                URI.create("http://maintenance.test"),
                URI.create("http://media.test"),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2)));
  }

  @Test
  void readsOnlyTheDedicatedCabinPhotoPresentationSnapshot() {
    UUID assetId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    server
        .expect(
            requestTo(
                "http://asset.test/api/internal/asset/v1/logistics/rental-items/"
                    + assetId
                    + "/photo-presentation-snapshot"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer test-asset.logistics"))
        .andRespond(
            withSuccess(
                """
                {
                  "assetId":"%s",
                  "version":4,
                  "warehouseId":"%s",
                  "number":"БК-004",
                  "dimensions":"2.4x6",
                  "finishing":"ДВП",
                  "category":"Обычная",
                  "characteristics":["Пластиковое окно","Электрика КК"],
                  "linoleum":true
                }
                """
                    .formatted(assetId, warehouseId),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.CabinPhotoPresentationAssetSnapshot snapshot =
        gateway.readCabinPhotoPresentationSnapshot(assetId);

    assertThat(snapshot.assetId()).isEqualTo(assetId);
    assertThat(snapshot.version()).isEqualTo(4);
    assertThat(snapshot.warehouseId()).isEqualTo(warehouseId);
    assertThat(snapshot.number()).isEqualTo("БК-004");
    assertThat(snapshot.dimensions()).isEqualTo("2.4x6");
    assertThat(snapshot.finishing()).isEqualTo("ДВП");
    assertThat(snapshot.category()).isEqualTo("Обычная");
    assertThat(snapshot.characteristics())
        .containsExactly("Пластиковое окно", "Электрика КК");
    assertThat(snapshot.linoleum()).isTrue();
    server.verify();
  }

  @Test
  void malformedCabinMediaSnapshotCollectionsBecomeNormalizedDependencyFailures() {
    UUID warehouseId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID mediaId = UUID.randomUUID();
    List<String> malformedResponses =
        List.of(
            "{\"items\":null}",
            "{\"items\":[null]}",
            "{\"items\":[{\"cabinId\":\"%s\",\"photoCount\":1,\"photos\":null}]}"
                .formatted(cabinId),
            """
            {"items":[{"cabinId":"%s","photoCount":1,"photos":[{
              "mediaId":"%s","generation":1,"sortOrder":0,"availableVariants":null
            }]}]}
            """
                .formatted(cabinId, mediaId));

    for (String response : malformedResponses) {
      server
          .expect(
              requestTo(
                  "http://media.test/api/internal/media/v1/logistics/cabin-presentations/snapshots"))
          .andExpect(method(HttpMethod.POST))
          .andExpect(header("Authorization", "Bearer test-media.logistics"))
          .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

      assertThatThrownBy(
              () -> gateway.readCabinMediaSnapshots(warehouseId, List.of(cabinId)))
          .isInstanceOf(LogisticsDependencyException.class)
          .hasMessageContaining("invalid cabin media");
      server.verify();
      server.reset();
    }
  }

  @Test
  void usesTheExactAssetScopeAndTypedReturnLeasePayload() {
    UUID key = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    server
        .expect(requestTo("http://asset.test/api/internal/asset/v1/logistics/operation-leases"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-asset.logistics"))
        .andExpect(header("Idempotency-Key", key.toString()))
        .andExpect(jsonPath("$.rentalItemId").value(assetId.toString()))
        .andExpect(jsonPath("$.ownerType").value("LOGISTICS_RETURN"))
        .andExpect(jsonPath("$.documentId").value(documentId.toString()))
        .andExpect(jsonPath("$.lineId").value(lineId.toString()))
        .andExpect(jsonPath("$.expectedRentalItemVersion").value(7))
        .andRespond(
            withSuccess(
                """
                {
                  "leaseId":"00000000-0000-0000-0000-000000000501",
                  "version":3,
                  "rentalItemId":"%s",
                  "fencingToken":11,
                  "state":"ACTIVE",
                  "expiresAt":"2026-07-17T12:00:00Z"
                }
                """
                    .formatted(assetId),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.OperationLease response =
        gateway.acquireReturnLease(key, assetId, 7, documentId, lineId);

    assertThat(response.rentalItemId()).isEqualTo(assetId);
    assertThat(response.fencingToken()).isEqualTo(11);
    server.verify();
  }

  @Test
  void recordsAdditionalReturnFurnitureThroughTheFrozenAssetReceiptContract() {
    UUID idempotencyKey = UUID.randomUUID();
    UUID returnId = UUID.randomUUID();
    UUID returnLineId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    UUID receiptId = UUID.randomUUID();
    UUID stockBalanceId = UUID.randomUUID();

    server
        .expect(
            requestTo(
                "http://asset.test/api/internal/asset/v1/logistics/return-equipment-receipts"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-asset.logistics"))
        .andExpect(header("Idempotency-Key", idempotencyKey.toString()))
        .andExpect(jsonPath("$.returnId").value(returnId.toString()))
        .andExpect(jsonPath("$.returnLineId").value(returnLineId.toString()))
        .andExpect(jsonPath("$.warehouseId").value(warehouseId.toString()))
        .andExpect(jsonPath("$.lines[0].equipmentId").value(equipmentId.toString()))
        .andExpect(jsonPath("$.lines[0].quantity").value(2))
        .andRespond(
            withStatus(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    """
                    {
                      "returnId":"%s",
                      "returnLineId":"%s",
                      "warehouseId":"%s",
                      "lines":[{
                        "receiptId":"%s",
                        "equipmentId":"%s",
                        "quantity":2,
                        "stockBalanceId":"%s",
                        "stockBalanceVersion":4,
                        "stockQuantity":7
                      }]
                    }
                    """
                        .formatted(
                            returnId,
                            returnLineId,
                            warehouseId,
                            receiptId,
                            equipmentId,
                            stockBalanceId)));

    LogisticsDependencyGateway.ReturnEquipmentReceipt receipt =
        gateway.receiveReturnEquipment(
            idempotencyKey,
            returnId,
            returnLineId,
            warehouseId,
            java.util.List.of(
                new LogisticsDependencyGateway.ReturnEquipmentReceiptLine(
                    null, equipmentId, 2, null, 0, 0)));

    assertThat(receipt.lines())
        .singleElement()
        .satisfies(
            line -> {
              assertThat(line.receiptId()).isEqualTo(receiptId);
              assertThat(line.stockBalanceId()).isEqualTo(stockBalanceId);
              assertThat(line.stockQuantity()).isEqualTo(7);
            });
    server.verify();
  }

  @Test
  void usesTheExactWarehouseScopeForTheNarrowIdentityRoute() {
    UUID warehouseId = UUID.randomUUID();
    server
        .expect(
            requestTo(
                "http://warehouse.test/api/internal/warehouse/v1/warehouses/logistics/"
                    + warehouseId
                    + "/identity"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer test-warehouse.logistics"))
        .andRespond(
            withSuccess(
                """
                {
                  "id":"%s",
                  "version":4,
                  "active":true,
                  "name":"СПБ",
                  "city":"Санкт-Петербург",
                  "timeZone":"Europe/Moscow"
                }
                """
                    .formatted(warehouseId),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.WarehouseIdentity identity =
        gateway.readWarehouseIdentity(warehouseId);

    assertThat(identity)
        .isEqualTo(
            new LogisticsDependencyGateway.WarehouseIdentity(
                warehouseId, 4, true, "СПБ", "Санкт-Петербург", "Europe/Moscow"));
    server.verify();
  }

  @Test
  void usesTheFrozenMaintenanceTransferRoutesAndStableIdempotencyKeys() {
    UUID key = UUID.randomUUID();
    UUID transferId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    UUID sourceWarehouseId = UUID.randomUUID();
    UUID targetWarehouseId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    String base =
        "http://maintenance.test/api/internal/maintenance/v1/logistics/transfers/"
            + transferId
            + "/lines/"
            + lineId;

    server
        .expect(requestTo(base + "/prepare-departure"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-maintenance.logistics"))
        .andExpect(header("Idempotency-Key", key.toString()))
        .andExpect(jsonPath("$.rentalItemId").value(rentalItemId.toString()))
        .andExpect(jsonPath("$.sourceWarehouseId").value(sourceWarehouseId.toString()))
        .andExpect(jsonPath("$.targetWarehouseId").value(targetWarehouseId.toString()))
        .andRespond(
            withSuccess(
                """
                {
                  "activeRepairId":"%s",
                  "activeRepairVersion":8,
                  "assetStatus":"REPAIR"
                }
                """
                    .formatted(repairId),
                MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(base + "/arrival-preflight"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-maintenance.logistics"))
        .andExpect(jsonPath("$.rentalItemId").value(rentalItemId.toString()))
        .andRespond(
            withSuccess(
                """
                {
                  "activeRepairId":"%s",
                  "priorityRequired":true,
                  "missingQueueDefinitionIds":[]
                }
                """
                    .formatted(repairId),
                MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(base + "/complete-arrival"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-maintenance.logistics"))
        .andExpect(header("Idempotency-Key", key.toString()))
        .andExpect(jsonPath("$.rentalItemVersion").value(9))
        .andExpect(jsonPath("$.priority").value(2))
        .andExpect(jsonPath("$.movementToShipment").doesNotExist())
        .andRespond(
            withSuccess(
                """
                {
                  "activeRepairId":"%s",
                  "repairVersion":9,
                  "warehouseId":"%s"
                }
                """
                    .formatted(repairId, targetWarehouseId),
                MediaType.APPLICATION_JSON));

    assertThat(
            gateway.prepareTransferDeparture(
                key, transferId, lineId, rentalItemId, sourceWarehouseId, targetWarehouseId))
        .isEqualTo(new LogisticsDependencyGateway.TransferRepairDeparture(repairId, 8L, "REPAIR"));
    assertThat(
            gateway.preflightTransferArrival(
                transferId, lineId, rentalItemId, sourceWarehouseId, targetWarehouseId))
        .isEqualTo(
            new LogisticsDependencyGateway.TransferRepairArrivalPreflight(
                repairId, true, List.of()));
    assertThat(
            gateway.completeTransferArrival(
                key, transferId, lineId, rentalItemId, 9, sourceWarehouseId, targetWarehouseId, 2))
        .isEqualTo(
            new LogisticsDependencyGateway.TransferRepairArrivalCompletion(
                repairId, 9L, targetWarehouseId));
    server.verify();
  }

  @Test
  void forwardsThePreparedRepairStatusToTheAssetTransferEffect() {
    UUID key = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    UUID leaseId = UUID.randomUUID();
    UUID transferId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID destinationWarehouseId = UUID.randomUUID();
    server
        .expect(
            requestTo(
                "http://asset.test/api/internal/asset/v1/logistics/rental-items/"
                    + assetId
                    + "/effects"))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(header("Idempotency-Key", key.toString()))
        .andExpect(jsonPath("$.action").value("TRANSFER_ARRIVE"))
        .andExpect(jsonPath("$.destinationWarehouseId").value(destinationWarehouseId.toString()))
        .andExpect(jsonPath("$.transferAssetStatus").value("REPAIR"))
        .andRespond(
            withSuccess(
                """
                {
                  "assetId":"%s",
                  "version":12,
                  "warehouseId":"%s",
                  "status":"REPAIR",
                  "contents":[]
                }
                """
                    .formatted(assetId, destinationWarehouseId),
                MediaType.APPLICATION_JSON));

    assertThat(
            gateway.applyFencedEffect(
                key,
                LogisticsDependencyGateway.AssetEffect.TRANSFER_ARRIVE,
                assetId,
                11,
                leaseId,
                7,
                LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_TRANSFER,
                transferId,
                lineId,
                destinationWarehouseId,
                "REPAIR"))
        .extracting(LogisticsDependencyGateway.RentalItemSnapshot::status)
        .isEqualTo("REPAIR");
    server.verify();
  }

  @Test
  void readsCabinFacetsWithTheInquiryHoldScope() {
    UUID warehouseId = UUID.randomUUID();
    UUID holdScopeId = UUID.randomUUID();
    server
        .expect(
            requestTo(
                "http://asset.test/api/internal/asset/v1/logistics/cabin-facets?warehouseId="
                    + warehouseId
                    + "&holdScopeId="
                    + holdScopeId))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer test-asset.logistics"))
        .andRespond(
            withSuccess(
                """
                {
                  "warehouseId":"%s",
                  "cabinTypes":["БК-1"],
                  "finishes":["ДВП"],
                  "dimensions":["6x2.4"],
                  "categories":["Новая"],
                  "characteristics":["Утеплённая","Электрика"],
                  "typeDimensions":[{"cabinType":"БК-1","dimensions":["6x2.4"]}]
                }
                """
                    .formatted(warehouseId),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.CabinFacets facets =
        gateway.readAvailableCabinFacets(warehouseId, holdScopeId);

    assertThat(facets.warehouseId()).isEqualTo(warehouseId);
    assertThat(facets.cabinTypes()).containsExactly("БК-1");
    assertThat(facets.characteristics()).containsExactly("Утеплённая", "Электрика");
    assertThat(facets.typeDimensions())
        .containsExactly(
            new LogisticsDependencyGateway.CabinTypeDimensionRelation("БК-1", List.of("6x2.4")));
    server.verify();
  }

  @Test
  void readsBoundedCabinCatalogFactsWithoutAWriteOrIdempotencyHeader() {
    UUID warehouseId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    server
        .expect(
            requestTo(
                "http://asset.test/api/internal/asset/v1/logistics/cabin-catalog"
                    + "?warehouseId="
                    + warehouseId
                    + "&query=CAB-001&page=0&size=20"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer test-asset.logistics"))
        .andRespond(
            withSuccess(
                """
                {
                  "warehouseId":"%s",
                  "content":[{
                    "id":"%s",
                    "version":4,
                    "warehouseId":"%s",
                    "status":"RENTED",
                    "number":"CAB-001",
                    "rentalType":"БК-1",
                    "dimensions":"6x2.4",
                    "finishing":"ДВП",
                    "category":"Обычная",
                    "characteristics":"Электрика",
                    "linoleum":true,
                    "passport":{},
                    "tags":[],
                    "contents":[],
                    "updatedAt":"2026-07-27T05:41:54Z"
                  }],
                  "page":0,
                  "size":20,
                  "totalElements":1,
                  "totalPages":1
                }
                """
                    .formatted(warehouseId, cabinId, warehouseId),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.CabinCatalogPage page =
        gateway.readCabinCatalog(warehouseId, "CAB-001", 0, 20);

    assertThat(page.content())
        .singleElement()
        .satisfies(
            cabin -> {
              assertThat(cabin.id()).isEqualTo(cabinId);
              assertThat(cabin.status()).isEqualTo("RENTED");
            });
    assertThat(page.totalElements()).isOne();
    server.verify();
  }

  @Test
  void preservesNullablePassportValuesAndExactCategoryInCabinSearchResults() {
    UUID warehouseId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID holdScopeId = UUID.randomUUID();
    UUID actorSubjectId = UUID.randomUUID();
    UUID downstreamKey = UUID.randomUUID();
    OffsetDateTime expiresAt = OffsetDateTime.parse("2026-07-27T06:00:00Z");
    String exactBody =
        "{\"warehouseId\":\""
            + warehouseId
            + "\",\"holdScopeId\":\""
            + holdScopeId
            + "\",\"expiresAt\":\"2026-07-27T06:00:00Z\",\"actorSubjectId\":\""
            + actorSubjectId
            + "\",\"actorRole\":\"RENTAL_MANAGER\",\"resultMode\":\"REPLACE\",\"groups\":[{\"cabinType\":null,"
            + "\"finish\":\"ДВП\",\"dimensions\":null,\"category\":\"Новая\","
            + "\"characteristics\":null,\"linoleum\":null,\"quantity\":2}]}";
    server
        .expect(requestTo("http://asset.test/api/internal/asset/v1/logistics/cabin-searches"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-asset.logistics"))
        .andExpect(header("Idempotency-Key", downstreamKey.toString()))
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(content().string(exactBody))
        .andExpect(jsonPath("$.warehouseId").value(warehouseId.toString()))
        .andExpect(jsonPath("$.holdScopeId").value(holdScopeId.toString()))
        .andExpect(jsonPath("$.expiresAt").value("2026-07-27T06:00:00Z"))
        .andExpect(jsonPath("$.actorSubjectId").value(actorSubjectId.toString()))
        .andExpect(jsonPath("$.actorRole").value("RENTAL_MANAGER"))
        .andExpect(jsonPath("$.resultMode").value("REPLACE"))
        .andExpect(jsonPath("$.groups[0].finish").value("ДВП"))
        .andExpect(jsonPath("$.groups[0].cabinType").doesNotExist())
        .andExpect(jsonPath("$.groups[0].dimensions").doesNotExist())
        .andExpect(jsonPath("$.groups[0].category").value("Новая"))
        .andExpect(jsonPath("$.groups[0].condition").doesNotExist())
        .andExpect(jsonPath("$.groups[0].quantity").value(2))
        .andRespond(
            withSuccess(
                """
                {
                  "warehouseId":"%s",
                  "expiresAt":"%s",
                  "groups":[{
                    "group":{"cabinType":null,"finish":"ДВП","dimensions":null,"category":"Новая","quantity":2},
                    "cabins":[{
                      "id":"%s",
                      "version":3,
                      "warehouseId":"%s",
                      "status":"FREE",
                      "number":"СПБ-001",
                      "rentalType":"БК-5",
                      "dimensions":"2x2",
                      "finishing":"ДВП",
                      "category":"Новая",
                      "characteristics":"Электрика",
                      "linoleum":true,
                      "passport":{"wall":"ДВП","legacy":null},
                      "tags":[],
                      "contents":[],
                      "updatedAt":"2026-07-27T05:41:54Z"
                    }]
                  }]
                }
                """
                    .formatted(warehouseId, expiresAt, cabinId, warehouseId),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.CabinSearchResult result =
        gateway.searchAvailableCabins(downstreamKey, exactBody);

    assertThat(result.expiresAt()).isEqualTo(expiresAt);
    assertThat(result.groups())
        .singleElement()
        .satisfies(
            group -> {
              assertThat(group.group().category()).isEqualTo("Новая");
              assertThat(group.cabins()).singleElement();
              assertThat(group.cabins().getFirst().status()).isEqualTo("FREE");
              assertThat(group.cabins().getFirst().category()).isEqualTo("Новая");
              assertThat(group.cabins().getFirst().passport())
                  .containsEntry("wall", "ДВП")
                  .containsKey("legacy");
              assertThat(group.cabins().getFirst().passport().get("legacy")).isNull();
            });
    server.verify();
  }

  @Test
  void forwardsRichCabinSearchGroupsAndReturnsEachGroupUnchanged() {
    UUID warehouseId = UUID.randomUUID();
    UUID firstCabinId = UUID.randomUUID();
    UUID secondCabinId = UUID.randomUUID();
    UUID holdScopeId = UUID.randomUUID();
    UUID actorSubjectId = UUID.randomUUID();
    UUID downstreamKey = UUID.randomUUID();
    OffsetDateTime expiresAt = OffsetDateTime.parse("2026-07-27T06:00:00Z");
    LogisticsDependencyGateway.CabinSearchGroup first =
        new LogisticsDependencyGateway.CabinSearchGroup(
            "БК-1", "ДВП", "6x2.4", "Новая", "Утеплённая с электрикой", true, 6);
    LogisticsDependencyGateway.CabinSearchGroup second =
        new LogisticsDependencyGateway.CabinSearchGroup(
            "БК-2", "OSB", "6x2.4", "ИТР", "С дополнительной вентиляцией", false, 6);
    String exactBody =
        "{\"warehouseId\":\""
            + warehouseId
            + "\",\"holdScopeId\":\""
            + holdScopeId
            + "\",\"expiresAt\":\"2026-07-27T06:00:00Z\",\"actorSubjectId\":\""
            + actorSubjectId
            + "\",\"actorRole\":\"RENTAL_MANAGER\",\"resultMode\":\"APPEND\",\"groups\":["
            + "{\"cabinType\":\"БК-1\",\"finish\":\"ДВП\",\"dimensions\":\"6x2.4\","
            + "\"category\":\"Новая\",\"characteristics\":\"Утеплённая с электрикой\","
            + "\"linoleum\":true,\"quantity\":6},"
            + "{\"cabinType\":\"БК-2\",\"finish\":\"OSB\",\"dimensions\":\"6x2.4\","
            + "\"category\":\"ИТР\",\"characteristics\":\"С дополнительной вентиляцией\","
            + "\"linoleum\":false,\"quantity\":6}]}";
    server
        .expect(requestTo("http://asset.test/api/internal/asset/v1/logistics/cabin-searches"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-asset.logistics"))
        .andExpect(header("Idempotency-Key", downstreamKey.toString()))
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(content().string(exactBody))
        .andExpect(jsonPath("$.resultMode").value("APPEND"))
        .andExpect(jsonPath("$.groups[0].cabinType").value("БК-1"))
        .andExpect(jsonPath("$.groups[0].finish").value("ДВП"))
        .andExpect(jsonPath("$.groups[0].dimensions").value("6x2.4"))
        .andExpect(jsonPath("$.groups[0].category").value("Новая"))
        .andExpect(jsonPath("$.groups[0].characteristics").value("Утеплённая с электрикой"))
        .andExpect(jsonPath("$.groups[0].linoleum").value(true))
        .andExpect(jsonPath("$.groups[0].quantity").value(6))
        .andExpect(jsonPath("$.groups[1].cabinType").value("БК-2"))
        .andExpect(jsonPath("$.groups[1].finish").value("OSB"))
        .andExpect(jsonPath("$.groups[1].dimensions").value("6x2.4"))
        .andExpect(jsonPath("$.groups[1].category").value("ИТР"))
        .andExpect(jsonPath("$.groups[1].characteristics").value("С дополнительной вентиляцией"))
        .andExpect(jsonPath("$.groups[1].linoleum").value(false))
        .andExpect(jsonPath("$.groups[1].quantity").value(6))
        .andRespond(
            withSuccess(
                """
                {
                  "warehouseId":"%s",
                  "expiresAt":"%s",
                  "groups":[
                    {
                      "group":{
                        "cabinType":"БК-1",
                        "finish":"ДВП",
                        "dimensions":"6x2.4",
                        "category":"Новая",
                        "characteristics":"Утеплённая с электрикой",
                        "linoleum":true,
                        "quantity":6
                      },
                      "cabins":[{
                        "id":"%s",
                        "version":3,
                        "warehouseId":"%s",
                        "status":"FREE",
                        "number":"СПБ-001",
                        "rentalType":"БК-1",
                        "dimensions":"6x2.4",
                        "finishing":"ДВП",
                        "category":"Новая",
                        "characteristics":"Утеплённая с электрикой",
                        "linoleum":true,
                        "passport":{},
                        "tags":[],
                        "contents":[],
                        "updatedAt":"2026-07-27T05:41:54Z"
                      }]
                    },
                    {
                      "group":{
                        "cabinType":"БК-2",
                        "finish":"OSB",
                        "dimensions":"6x2.4",
                        "category":"ИТР",
                        "characteristics":"С дополнительной вентиляцией",
                        "linoleum":false,
                        "quantity":6
                      },
                      "cabins":[{
                        "id":"%s",
                        "version":3,
                        "warehouseId":"%s",
                        "status":"FREE",
                        "number":"СПБ-002",
                        "rentalType":"БК-2",
                        "dimensions":"6x2.4",
                        "finishing":"OSB",
                        "category":"ИТР",
                        "characteristics":"С дополнительной вентиляцией",
                        "linoleum":false,
                        "passport":{},
                        "tags":[],
                        "contents":[],
                        "updatedAt":"2026-07-27T05:41:54Z"
                      }]
                    }
                  ]
                }
                """
                    .formatted(
                        warehouseId,
                        expiresAt,
                        firstCabinId,
                        warehouseId,
                        secondCabinId,
                        warehouseId),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.CabinSearchResult result =
        gateway.searchAvailableCabins(downstreamKey, exactBody);

    assertThat(result.groups())
        .extracting(LogisticsDependencyGateway.CabinSearchGroupResult::group)
        .containsExactly(first, second);
    assertThat(result.groups().get(0).cabins())
        .extracting(LogisticsDependencyGateway.AvailableCabin::status)
        .containsExactly("FREE");
    assertThat(result.groups().get(1).cabins())
        .extracting(LogisticsDependencyGateway.AvailableCabin::status)
        .containsExactly("FREE");
    server.verify();
  }

  @Test
  void readsPresentationHoldsWithTheExactAssetScope() {
    UUID holdScopeId = UUID.randomUUID();
    UUID holdId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    server
        .expect(
            requestTo(
                "http://asset.test/api/internal/asset/v1/logistics/presentations/"
                    + holdScopeId
                    + "/holds"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer test-asset.logistics"))
        .andRespond(
            withSuccess(
                """
                {
                  "presentationId":"%s",
                  "expiresAt":"2026-07-27T06:10:00Z",
                  "holds":[{
                    "holdId":"%s",
                    "version":1,
                    "presentationId":"%s",
                    "rentalItemId":"%s",
                    "warehouseId":"%s",
                    "state":"ACTIVE",
                    "expiresAt":"2026-07-27T06:10:00Z",
                    "orderId":null,
                    "createdAt":"2026-07-27T06:00:00Z",
                    "endedAt":null
                  }]
                }
                """
                    .formatted(holdScopeId, holdId, holdScopeId, cabinId, warehouseId),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.PresentationHolds result =
        gateway.readPresentationHolds(holdScopeId);

    assertThat(result.presentationId()).isEqualTo(holdScopeId);
    assertThat(result.holds())
        .singleElement()
        .satisfies(
            hold -> {
              assertThat(hold.holdId()).isEqualTo(holdId);
              assertThat(hold.rentalItemId()).isEqualTo(cabinId);
              assertThat(hold.state()).isEqualTo("ACTIVE");
            });
    server.verify();
  }

  @Test
  void readsActorOwnedManualDraftHoldsWithExactActorQuery() {
    UUID holdScopeId = UUID.randomUUID();
    UUID actorSubjectId = UUID.randomUUID();
    server
        .expect(
            requestTo(
                "http://asset.test/api/internal/asset/v1/logistics/presentations/"
                    + holdScopeId
                    + "/holds?actorSubjectId="
                    + actorSubjectId
                    + "&actorRole=RENTAL_MANAGER"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer test-asset.logistics"))
        .andRespond(
            withSuccess(
                """
                {"presentationId":"%s","expiresAt":null,"holds":[]}
                """
                    .formatted(holdScopeId),
                MediaType.APPLICATION_JSON));

    assertThat(gateway.readPresentationHolds(holdScopeId, actorSubjectId, "RENTAL_MANAGER"))
        .satisfies(result -> assertThat(result.holds()).isEmpty());
    server.verify();
  }

  @Test
  void sendsFrozenSelectionReplaceAndReleaseBodiesWithoutRebuildingThem() {
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID actorSubjectId = UUID.randomUUID();
    UUID replaceKey = UUID.randomUUID();
    UUID releaseKey = UUID.randomUUID();
    String replaceBody =
        "{\"warehouseId\":\""
            + warehouseId
            + "\",\"rentalItemIds\":[\""
            + cabinId
            + "\"],\"expiresAt\":\"2026-08-09T12:30:00Z\",\"actorSubjectId\":\""
            + actorSubjectId
            + "\",\"actorRole\":\"RENTAL_MANAGER\",\"sourceHoldScopeId\":null}";
    String releaseBody =
        "{\"actorSubjectId\":\"" + actorSubjectId + "\",\"actorRole\":\"RENTAL_MANAGER\"}";
    String holdsUri =
        "http://asset.test/api/internal/asset/v1/logistics/presentations/" + inquiryId + "/holds";
    server
        .expect(requestTo(holdsUri))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(header("Authorization", "Bearer test-asset.logistics"))
        .andExpect(header("Idempotency-Key", replaceKey.toString()))
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(content().string(replaceBody))
        .andRespond(
            withSuccess(
                """
                {
                  "presentationId":"%s",
                  "expiresAt":"2026-08-09T12:30:00Z",
                  "holds":[],
                  "cabins":[{
                    "id":"%s","version":0,"warehouseId":"%s","status":"FREE",
                    "number":"СПБ-001","passport":{},"tags":[],"contents":[],
                    "updatedAt":"2026-08-09T12:00:00Z"
                  }]
                }
                """
                    .formatted(inquiryId, cabinId, warehouseId),
                MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(holdsUri + "/release"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-asset.logistics"))
        .andExpect(header("Idempotency-Key", releaseKey.toString()))
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(content().string(releaseBody))
        .andRespond(
            withSuccess(
                """
                {"presentationId":"%s","expiresAt":null,"holds":[]}
                """
                    .formatted(inquiryId),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.PresentationHolds replaced =
        gateway.replacePresentationHoldsExact(replaceKey, inquiryId, replaceBody);
    LogisticsDependencyGateway.PresentationHolds released =
        gateway.releasePresentationHoldsExact(releaseKey, inquiryId, releaseBody);

    assertThat(replaced.presentationId()).isEqualTo(inquiryId);
    assertThat(replaced.expiresAt()).isEqualTo(OffsetDateTime.parse("2026-08-09T12:30:00Z"));
    assertThat(released.presentationId()).isEqualTo(inquiryId);
    assertThat(released.expiresAt()).isNull();
    server.verify();
  }

  @Test
  void transfersManualDraftScopeWhenReplacingPresentationHolds() {
    UUID idempotencyKey = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID manualDraftId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID actorSubjectId = UUID.randomUUID();
    OffsetDateTime expiresAt = OffsetDateTime.parse("2026-07-27T07:00:00Z");
    server
        .expect(
            requestTo(
                "http://asset.test/api/internal/asset/v1/logistics/presentations/"
                    + inquiryId
                    + "/holds"))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(header("Authorization", "Bearer test-asset.logistics"))
        .andExpect(header("Idempotency-Key", idempotencyKey.toString()))
        .andExpect(jsonPath("$.sourceHoldScopeId").value(manualDraftId.toString()))
        .andRespond(
            withSuccess(
                """
                {
                  "presentationId":"%s",
                  "expiresAt":"%s",
                  "holds":[],
                  "cabins":[{
                    "id":"%s","version":0,"warehouseId":"%s","status":"FREE",
                    "number":"СПБ-001","passport":{},"tags":[],"contents":[],
                    "updatedAt":"2026-07-27T06:30:00Z"
                  }]
                }
                """
                    .formatted(inquiryId, expiresAt, cabinId, warehouseId),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.PresentationHolds result =
        gateway.replacePresentationHolds(
            idempotencyKey,
            inquiryId,
            warehouseId,
            List.of(cabinId),
            expiresAt,
            actorSubjectId,
            "RENTAL_MANAGER",
            manualDraftId);

    assertThat(result.presentationId()).isEqualTo(inquiryId);
    server.verify();
  }

  @Test
  void rejectsACombinedOrBroaderClientTokenBeforeSendingARequest() {
    OAuth2AuthorizedClient combined = mock(OAuth2AuthorizedClient.class);
    when(combined.getAccessToken())
        .thenReturn(
            new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "combined",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Set.of("asset.logistics", "warehouse.logistics")));
    doReturn(combined).when(authorizedClients).authorize(any());

    assertThatThrownBy(() -> gateway.readRentalItemSnapshot(UUID.randomUUID()))
        .isInstanceOf(LogisticsDependencyException.class)
        .extracting(exception -> ((LogisticsDependencyException) exception).kind())
        .isEqualTo(LogisticsDependencyException.FailureKind.CONFIGURATION);
    server.verify();
  }

  @Test
  void reservesAnOrderUnitWithTheFrozenNestedAssetShapeAndIdempotencyHeader() {
    UUID key = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    String tenantSnapshot = "ООО Тестовый клиент";
    UUID actorId = UUID.randomUUID();
    UUID reservationId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    server
        .expect(
            requestTo(
                "http://asset.test/api/internal/asset/v1/logistics/orders/" + orderId + "/units"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-asset.logistics"))
        .andExpect(header("Idempotency-Key", key.toString()))
        .andExpect(jsonPath("$.warehouseId").value(warehouseId.toString()))
        .andExpect(jsonPath("$.rentalItemId").value(unitId.toString()))
        .andExpect(jsonPath("$.clientId").value(clientId.toString()))
        .andExpect(jsonPath("$.tenantSnapshot").value(tenantSnapshot))
        .andExpect(jsonPath("$.actorSubjectId").value(actorId.toString()))
        .andExpect(jsonPath("$.actorRole").value("RENTAL_MANAGER"))
        .andRespond(
            withSuccess(
                """
                {
                  "reservationId":"%s",
                  "reservationVersion":0,
                  "orderId":"%s",
                  "rentalItemId":"%s",
                  "warehouseId":"%s",
                  "state":"ACTIVE",
                  "addedBySubjectId":"%s",
                  "addedByRole":"RENTAL_MANAGER",
                  "createdAt":"2026-07-19T12:00:00Z",
                  "releasedAt":null,
                  "replayed":false,
                  "unit":{
                    "id":"%s",
                    "version":3,
                    "warehouseId":"%s",
                    "number":"CAB-17",
                    "status":"FREE",
                    "rentalType":"STANDARD",
                    "dimensions":"6x2.4",
                    "finishing":"BASIC",
                    "category":"OFFICE",
                    "characteristics":"Утеплённая",
                    "linoleum":true,
                    "tags":[],
                    "contents":[{
                      "equipmentId":"%s",
                      "equipmentName":"Стул",
                      "quantity":2,
                      "locationKind":"CABIN_NON_RENTED"
                    }],
                    "createdAt":"2026-07-01T12:00:00Z",
                    "updatedAt":"2026-07-19T12:00:00Z"
                  }
                }
                """
                    .formatted(
                        reservationId,
                        orderId,
                        unitId,
                        warehouseId,
                        actorId,
                        unitId,
                        warehouseId,
                        equipmentId),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.OrderUnitReservation response =
        gateway.reserveOrderUnit(
            key,
            orderId,
            warehouseId,
            unitId,
            clientId,
            tenantSnapshot,
            null,
            actorId,
            "RENTAL_MANAGER");

    assertThat(response.reservationId()).isEqualTo(reservationId);
    assertThat(response.unitId()).isEqualTo(unitId);
    assertThat(response.unit().characteristics()).isEqualTo("Утеплённая");
    assertThat(response.unit().linoleum()).isTrue();
    assertThat(response.unit().contents())
        .singleElement()
        .satisfies(
            content -> {
              assertThat(content.equipmentId()).isEqualTo(equipmentId);
              assertThat(content.quantity()).isEqualTo(2);
            });
    server.verify();
  }

  @Test
  void propagatesTheSafeUnitReservationConflictCode() {
    UUID key = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    UUID actorId = UUID.randomUUID();
    server
        .expect(
            requestTo(
                "http://asset.test/api/internal/asset/v1/logistics/orders/" + orderId + "/units"))
        .andRespond(
            withStatus(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(
                    """
                    {"status":409,"code":"UNIT_ALREADY_RESERVED"}
                    """));

    assertThatThrownBy(
            () ->
                gateway.reserveOrderUnit(
                    key,
                    orderId,
                    warehouseId,
                    unitId,
                    clientId,
                    "ООО Тестовый клиент",
                    null,
                    actorId,
                    "RENTAL_MANAGER"))
        .isInstanceOf(LogisticsDependencyException.class)
        .satisfies(
            exception -> {
              LogisticsDependencyException dependency = (LogisticsDependencyException) exception;
              assertThat(dependency.kind())
                  .isEqualTo(LogisticsDependencyException.FailureKind.PERMANENT_REJECTION);
              assertThat(dependency.dependencyCode()).isEqualTo("UNIT_ALREADY_RESERVED");
            });
    server.verify();
  }

  @Test
  void cabinSearchRejectsOnlyClassifiedBadRequestOrConflictResponsesPermanently() {
    assertCabinSearchFailure(
        HttpStatus.BAD_REQUEST,
        "{\"status\":400,\"code\":\"ASSET_VALIDATION_FAILED\"}",
        LogisticsDependencyException.FailureKind.PERMANENT_REJECTION,
        "ASSET_VALIDATION_FAILED");
    assertCabinSearchFailure(
        HttpStatus.CONFLICT,
        "{\"status\":409,\"code\":\"UNIT_PRESENTATION_HELD\"}",
        LogisticsDependencyException.FailureKind.PERMANENT_REJECTION,
        "UNIT_PRESENTATION_HELD");
    assertCabinSearchFailure(
        HttpStatus.UNAUTHORIZED,
        "{\"status\":401,\"code\":\"ASSET_UNAUTHORIZED\"}",
        LogisticsDependencyException.FailureKind.TRANSIENT,
        null);
    assertCabinSearchFailure(
        HttpStatus.FORBIDDEN,
        "{\"status\":403,\"code\":\"ASSET_FORBIDDEN\"}",
        LogisticsDependencyException.FailureKind.TRANSIENT,
        null);
    assertCabinSearchFailure(
        HttpStatus.NOT_FOUND,
        "{\"status\":404,\"code\":\"ASSET_NOT_FOUND\"}",
        LogisticsDependencyException.FailureKind.TRANSIENT,
        null);
    assertCabinSearchFailure(
        HttpStatus.SERVICE_UNAVAILABLE,
        "{\"status\":503,\"code\":\"ASSET_UNAVAILABLE\"}",
        LogisticsDependencyException.FailureKind.TRANSIENT,
        null);
    server.verify();
  }

  /** Verifies one status-specific cabin-search outcome without retaining its Problem body. */
  private void assertCabinSearchFailure(
      HttpStatus status,
      String body,
      LogisticsDependencyException.FailureKind expectedKind,
      String expectedCode) {
    UUID downstreamKey = UUID.randomUUID();
    String exactBody = "{\"probe\":\"" + downstreamKey + "\"}";
    server
        .expect(requestTo("http://asset.test/api/internal/asset/v1/logistics/cabin-searches"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Idempotency-Key", downstreamKey.toString()))
        .andExpect(content().string(exactBody))
        .andRespond(withStatus(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body));

    assertThatThrownBy(() -> gateway.searchAvailableCabins(downstreamKey, exactBody))
        .isInstanceOf(LogisticsDependencyException.class)
        .satisfies(
            exception -> {
              LogisticsDependencyException dependency = (LogisticsDependencyException) exception;
              assertThat(dependency.kind()).isEqualTo(expectedKind);
              assertThat(dependency.dependencyCode()).isEqualTo(expectedCode);
            });
    server.verify();
    server.reset();
  }

  @Test
  void upsertsStructuredTransferOwnerProofWithTheExactMediaScope() {
    UUID documentId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID destinationWarehouseId = UUID.randomUUID();
    UUID proofEventId = UUID.randomUUID();
    server
        .expect(requestTo("http://media.test/api/internal/media/v1/owner-proofs"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-media.logistics"))
        .andExpect(jsonPath("$.ownerType").value("LOGISTICS_TRANSFER"))
        .andExpect(jsonPath("$.ownerId").doesNotExist())
        .andExpect(jsonPath("$.documentId").value(documentId.toString()))
        .andExpect(jsonPath("$.lineId").value(lineId.toString()))
        .andExpect(jsonPath("$.warehouseId").value(destinationWarehouseId.toString()))
        .andExpect(jsonPath("$.ownerRevision").value(0))
        .andExpect(jsonPath("$.aggregateVersion").value(0))
        .andExpect(jsonPath("$.proofEventId").value(proofEventId.toString()))
        .andExpect(jsonPath("$.active").value(true))
        .andRespond(
            withSuccess(
                """
                {
                  "ownerType":"LOGISTICS_TRANSFER",
                  "documentId":"%s",
                  "lineId":"%s",
                  "warehouseId":"%s",
                  "ownerRevision":0,
                  "aggregateVersion":0,
                  "proofEventId":"%s",
                  "active":true
                }
                """
                    .formatted(documentId, lineId, destinationWarehouseId, proofEventId),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.MediaOwnerProof proof =
        gateway.upsertMediaOwnerProof(
            LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_TRANSFER,
            documentId,
            lineId,
            destinationWarehouseId,
            0,
            0,
            proofEventId,
            true);

    assertThat(proof.documentId()).isEqualTo(documentId);
    assertThat(proof.lineId()).isEqualTo(lineId);
    assertThat(proof.warehouseId()).isEqualTo(destinationWarehouseId);
    assertThat(proof.proofEventId()).isEqualTo(proofEventId);
    server.verify();
  }

  @Test
  void refusesABroaderTokenBeforeSendingAnOwnerProof() {
    OAuth2AuthorizedClient combined = mock(OAuth2AuthorizedClient.class);
    when(combined.getAccessToken())
        .thenReturn(
            new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "combined",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Set.of("media.logistics", "asset.logistics")));
    doReturn(combined).when(authorizedClients).authorize(any());

    assertThatThrownBy(
            () ->
                gateway.upsertMediaOwnerProof(
                    LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_RETURN,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    0,
                    0,
                    UUID.randomUUID(),
                    true))
        .isInstanceOf(LogisticsDependencyException.class)
        .extracting(exception -> ((LogisticsDependencyException) exception).kind())
        .isEqualTo(LogisticsDependencyException.FailureKind.CONFIGURATION);
    server.verify();
  }

  @Test
  void serializesAllocatableRebalancePurposeForEquipmentMovementReservations() {
    assertEquipmentMovementReservationPurpose(
        LogisticsDependencyGateway.EquipmentMovementPurpose.ALLOCATABLE_REBALANCE);
  }

  @Test
  void serializesMaintenanceDispositionPurposeForEquipmentMovementReservations() {
    assertEquipmentMovementReservationPurpose(
        LogisticsDependencyGateway.EquipmentMovementPurpose.MAINTENANCE_DISPOSITION);
  }

  @Test
  void serializesExactReplacementReservationReplayContext() {
    UUID movementId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID sourceUnitId = UUID.randomUUID();
    UUID targetUnitId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID sourceReservationId = UUID.randomUUID();
    UUID reservationId = UUID.randomUUID();
    UUID sourceBalanceId = UUID.randomUUID();
    OffsetDateTime deadline = OffsetDateTime.parse("2026-08-11T15:00:00Z");
    List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements> units =
        List.of(
            new LogisticsDependencyGateway.OrderUnitEquipmentRequirements(
                targetUnitId,
                List.of(new LogisticsDependencyGateway.OrderEquipmentRequirement(equipmentId, 4))));
    server
        .expect(
            requestTo(
                "http://asset.test/api/internal/asset/v1/logistics/equipment-movement-reservations"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.orderId").value(orderId.toString()))
        .andExpect(jsonPath("$.targetRentalItemId").value(targetUnitId.toString()))
        .andExpect(
            jsonPath("$.replacementSourceReservationId").value(sourceReservationId.toString()))
        .andExpect(jsonPath("$.units[0].rentalItemId").value(targetUnitId.toString()))
        .andExpect(jsonPath("$.units[0].requirements[0].quantity").value(4))
        .andRespond(
            withSuccess(
                """
                {"reservationId":"%s","version":0,"ownerType":"LOGISTICS_EQUIPMENT_MOVEMENT",
                 "movementId":"%s","lineId":"%s","equipmentId":"%s",
                 "equipmentName":"Кровать","sourceBalanceId":"%s","sourceWarehouseId":"%s",
                 "sourceRentalItemId":"%s","sourceLocationKind":"CABIN_NON_RENTED",
                 "quantity":4,"state":"ACTIVE","reservedUntil":"%s","executedAt":null}
                """
                    .formatted(
                        reservationId,
                        movementId,
                        lineId,
                        equipmentId,
                        sourceBalanceId,
                        warehouseId,
                        sourceUnitId,
                        deadline),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.EquipmentMovementReservation reservation =
        gateway.acquireEquipmentMovementReservation(
            lineId,
            movementId,
            lineId,
            equipmentId,
            warehouseId,
            sourceUnitId,
            "CABIN_NON_RENTED",
            8,
            4,
            deadline,
            LogisticsDependencyGateway.EquipmentMovementPurpose.ALLOCATABLE_REBALANCE,
            orderId,
            targetUnitId,
            units,
            sourceReservationId);

    assertThat(reservation.reservationId()).isEqualTo(reservationId);
    server.verify();
  }

  @Test
  void rejectsMissingEquipmentMovementReservationPurposeBeforeSendingTheRequest() {
    assertThatThrownBy(
            () ->
                gateway.acquireEquipmentMovementReservation(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    null,
                    "STOCK",
                    4,
                    2,
                    OffsetDateTime.now(ZoneOffset.UTC).plusHours(1),
                    null))
        .isInstanceOf(LogisticsDependencyException.class)
        .extracting(exception -> ((LogisticsDependencyException) exception).kind())
        .isEqualTo(LogisticsDependencyException.FailureKind.CONFIGURATION);
    server.verify();
  }

  private void assertEquipmentMovementReservationPurpose(
      LogisticsDependencyGateway.EquipmentMovementPurpose purpose) {
    UUID movementId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID reservationId = UUID.randomUUID();
    UUID sourceBalanceId = UUID.randomUUID();
    OffsetDateTime deadline = OffsetDateTime.of(2026, 7, 20, 15, 0, 0, 0, ZoneOffset.UTC);

    server
        .expect(
            requestTo(
                "http://asset.test/api/internal/asset/v1/logistics/equipment-movement-reservations"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-asset.logistics"))
        .andExpect(header("Idempotency-Key", lineId.toString()))
        .andExpect(jsonPath("$.movementId").value(movementId.toString()))
        .andExpect(jsonPath("$.lineId").value(lineId.toString()))
        .andExpect(jsonPath("$.equipmentId").value(equipmentId.toString()))
        .andExpect(jsonPath("$.sourceWarehouseId").value(warehouseId.toString()))
        .andExpect(jsonPath("$.sourceLocationKind").value("STOCK"))
        .andExpect(jsonPath("$.expectedSourceBalanceVersion").value(4))
        .andExpect(jsonPath("$.quantity").value(2))
        .andExpect(jsonPath("$.reservedUntil").value("2026-07-20T15:00:00Z"))
        .andExpect(jsonPath("$.purpose").value(purpose.name()))
        .andRespond(
            withSuccess(
                """
                {"reservationId":"%s","version":0,"ownerType":"LOGISTICS_EQUIPMENT_MOVEMENT",
                 "movementId":"%s","lineId":"%s","equipmentId":"%s",
                 "equipmentName":"Стол","sourceBalanceId":"%s","sourceWarehouseId":"%s",
                 "sourceRentalItemId":null,"sourceLocationKind":"STOCK","quantity":2,"state":"ACTIVE",
                 "reservedUntil":"%s","executedAt":null}
                """
                    .formatted(
                        reservationId,
                        movementId,
                        lineId,
                        equipmentId,
                        sourceBalanceId,
                        warehouseId,
                        deadline),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.EquipmentMovementReservation reservation =
        gateway.acquireEquipmentMovementReservation(
            lineId,
            movementId,
            lineId,
            equipmentId,
            warehouseId,
            null,
            "STOCK",
            4,
            2,
            deadline,
            purpose);
    assertThat(reservation.equipmentName()).isEqualTo("Стол");
    server.verify();
  }

  @Test
  void registersFrozenEquipmentMovementWorkerTask() {
    UUID movementId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID boardTaskId = UUID.randomUUID();
    OffsetDateTime deadline = OffsetDateTime.of(2026, 7, 20, 15, 0, 0, 0, ZoneOffset.UTC);
    server
        .expect(
            requestTo(
                "http://task-board.test/api/internal/task-board/v1/logistics/equipment-movement-tasks"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-task-board.logistics"))
        .andExpect(jsonPath("$.warehouseId").value(warehouseId.toString()))
        .andExpect(jsonPath("$.externalTaskId").value(movementId.toString()))
        .andExpect(jsonPath("$.unitNumber").value("CAB-17"))
        .andExpect(jsonPath("$.plannedDurationMinutes").value(10))
        .andExpect(jsonPath("$.deadlineAt").value("2026-07-20T15:00:00Z"))
        .andExpect(jsonPath("$.operations[0].direction").value("BRING_TO_CABIN"))
        .andExpect(jsonPath("$.operations[0].equipmentId").value(movementId.toString()))
        .andRespond(
            withSuccess(
                """
                {"taskId":"%s","taskVersion":0,"warehouseId":"%s",
                 "externalTaskId":"%s","status":"ACTIVE","doneAt":null}
                """
                    .formatted(boardTaskId, warehouseId, movementId),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.EquipmentMovementBoardTask boardTask =
        gateway.registerEquipmentMovementTask(
            warehouseId,
            movementId,
            "CAB-17",
            10,
            deadline,
            java.util.List.of(
                new LogisticsDependencyGateway.EquipmentMovementOperation(
                    "BRING_TO_CABIN", movementId, "Стол", 2)));
    assertThat(boardTask.taskId()).isEqualTo(boardTaskId);
    server.verify();
  }

  @Test
  void createsTheReturnEstimateSourceWithInspectionDateAndPhotos() {
    UUID returnId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    UUID mediaId = UUID.randomUUID();
    UUID estimateId = UUID.randomUUID();
    LocalDate dispatchDate = LocalDate.parse("2026-07-27");
    OffsetDateTime arrivedAt = OffsetDateTime.parse("2026-07-27T10:30:00Z");
    server
        .expect(
            requestTo(
                "http://maintenance.test/api/internal/maintenance/v1/logistics/returns/"
                    + returnId
                    + "/lines/"
                    + lineId
                    + "/estimate-source"))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(header("Authorization", "Bearer test-maintenance.logistics"))
        .andExpect(jsonPath("$.warehouseId").value(warehouseId.toString()))
        .andExpect(jsonPath("$.rentalItemId").value(rentalItemId.toString()))
        .andExpect(jsonPath("$.rentalItemVersion").value(8))
        .andExpect(jsonPath("$.dispatchDate").value("2026-07-27"))
        .andExpect(jsonPath("$.arrivedAt").value("2026-07-27T10:30:00Z"))
        .andExpect(jsonPath("$.mediaReferences[0].mediaId").value(mediaId.toString()))
        .andExpect(jsonPath("$.mediaReferences[0].generation").value(4))
        .andExpect(jsonPath("$.shortages").doesNotExist())
        .andRespond(
            withSuccess(
                """
                {
                  "returnId":"%s",
                  "lineId":"%s",
                  "sourceVersion":0,
                  "warehouseId":"%s",
                  "rentalItemId":"%s",
                  "rentalItemVersion":8,
                  "estimateId":"%s",
                  "snapshotSha256":"%s",
                  "receivedAt":"2026-07-27T12:00:00Z"
                }
                """
                    .formatted(
                        returnId, lineId, warehouseId, rentalItemId, estimateId, "a".repeat(64)),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.ReturnEstimateSource source =
        gateway.upsertReturnEstimateSource(
            returnId,
            lineId,
            warehouseId,
            rentalItemId,
            8,
            dispatchDate,
            arrivedAt,
            List.of(new LogisticsDependencyGateway.MediaReference(mediaId, 4)));

    assertThat(source.estimateId()).isEqualTo(estimateId);
    server.verify();
  }

  @Test
  void executesFrozenEquipmentMovementReservationBatch() {
    UUID movementId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID reservationId = UUID.randomUUID();
    UUID sourceBalanceId = UUID.randomUUID();
    UUID targetBalanceId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    server
        .expect(
            requestTo(
                "http://asset.test/api/internal/asset/v1/logistics/equipment-movement-reservations/execute"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Idempotency-Key", movementId.toString()))
        .andExpect(jsonPath("$.movementId").value(movementId.toString()))
        .andExpect(jsonPath("$.lines[0].reservationId").value(reservationId.toString()))
        .andExpect(jsonPath("$.lines[0].targetWarehouseId").value(warehouseId.toString()))
        .andExpect(jsonPath("$.lines[0].targetRentalItemId").value(cabinId.toString()))
        .andExpect(jsonPath("$.lines[0].targetLocationKind").value("CABIN_NON_RENTED"))
        .andRespond(
            withSuccess(
                """
                {"movementId":"%s","lines":[{"reservationId":"%s","reservationVersion":1,
                "lineId":"%s","movement":{"id":"%s","version":0,"equipmentId":"%s",
                "sourceBalanceId":"%s","targetBalanceId":"%s","quantity":2,"kind":"STOCK_TO_CABIN",
                "occurredAt":"2026-07-20T14:55:00Z"}}]}
                """
                    .formatted(
                        movementId,
                        reservationId,
                        lineId,
                        eventId,
                        equipmentId,
                        sourceBalanceId,
                        targetBalanceId),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.EquipmentMovementExecution execution =
        gateway.executeEquipmentMovement(
            movementId,
            movementId,
            java.util.List.of(
                new LogisticsDependencyGateway.EquipmentMovementExecutionRequestLine(
                    reservationId, 0, lineId, warehouseId, cabinId, "CABIN_NON_RENTED")));
    assertThat(execution.lines())
        .singleElement()
        .satisfies(line -> assertThat(line.reservationVersion()).isOne());
    server.verify();
  }

  @Test
  void registersDriverTaskWithoutObsoleteDailyCapacity() {
    UUID taskId = UUID.randomUUID();
    UUID externalTaskId = UUID.randomUUID();
    UUID sourceId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID queueDefinitionId = UUID.randomUUID();
    UUID queueId = UUID.randomUUID();
    UUID driverId = UUID.randomUUID();
    LocalDate scheduledDate = LocalDate.parse("2026-08-03");
    server
        .expect(requestTo("http://task-board.test/api/internal/task-board/v1/tasks"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-task-board.logistics"))
        .andExpect(jsonPath("$.warehouseId").value(warehouseId.toString()))
        .andExpect(jsonPath("$.externalTaskId").value(externalTaskId.toString()))
        .andExpect(jsonPath("$.scheduledDate").value(scheduledDate.toString()))
        .andExpect(jsonPath("$.priority").value(2))
        .andExpect(jsonPath("$.dailyCapacity").doesNotExist())
        .andExpect(jsonPath("$.route[0].queueDefinitionId").value(queueDefinitionId.toString()))
        .andExpect(jsonPath("$.source.type").value("LOGISTICS_DRIVER_TASK"))
        .andExpect(jsonPath("$.source.sourceId").value(sourceId.toString()))
        .andExpect(jsonPath("$.lane").value("SCHEDULED"))
        .andExpect(jsonPath("$.driverAudience.mode").value("ASSIGNED_DRIVER"))
        .andExpect(jsonPath("$.driverAudience.workerId").value(driverId.toString()))
        .andExpect(jsonPath("$.driverAudience.workerName").value("Петров Пётр"))
        .andRespond(
            withSuccess(
                """
                {
                  "taskId":"%s","taskVersion":0,"warehouseId":"%s",
                  "externalTaskId":"%s","title":"Доставить бытовку",
                  "unitNumber":"СПБ-001","description":"Доставить бытовку",
                  "driverAudience":{"mode":"ASSIGNED_DRIVER","workerId":"%s",
                    "workerName":"Петров Пётр"},
                  "status":"ACTIVE","plannedDurationMinutes":null,"deadlineAt":null,
                  "scheduledDate":"%s","lane":"SCHEDULED","priority":2,
                  "pinned":false,"doneAt":null,
                  "route":[{
                    "entryId":"%s","entryVersion":0,
                    "queueDefinitionId":"%s","workQueueId":"%s","queueName":"Водители",
                    "routeIndex":0,"queuePosition":0,"entryType":"REAL",
                    "status":"WAITING","taskText":"Доставить бытовку",
                    "plannedDurationMinutes":null
                  }]
                }
                """
                    .formatted(
                        taskId,
                        warehouseId,
                        externalTaskId,
                        driverId,
                        scheduledDate,
                        entryId,
                        queueDefinitionId,
                        queueId),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.DriverBoardTask registered =
        gateway.registerDriverTask(
            warehouseId,
            externalTaskId,
            sourceId,
            "Доставить бытовку",
            "СПБ-001",
            "Доставить бытовку",
            queueDefinitionId,
            scheduledDate,
            2,
            new LogisticsDependencyGateway.DriverTaskAudience(
                dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskAudienceMode.ASSIGNED_DRIVER,
                driverId,
                "Петров Пётр"));

    assertThat(registered.externalTaskId()).isEqualTo(externalTaskId);
    assertThat(registered.scheduledDate()).isEqualTo(scheduledDate);
    assertThat(registered.lane()).isEqualTo("SCHEDULED");
    assertThat(registered.driverAudience().workerId()).isEqualTo(driverId);
    server.verify();
  }

  @Test
  void movesTheSameDriverTaskThroughThePrivateTaskBoardBoundary() {
    UUID externalTaskId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID queueDefinitionId = UUID.randomUUID();
    UUID queueId = UUID.randomUUID();
    LocalDate targetDate = LocalDate.parse("2026-08-03");
    server
        .expect(
            requestTo(
                "http://task-board.test/api/internal/task-board/v1/logistics/tasks/"
                    + externalTaskId
                    + "/move"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-task-board.logistics"))
        .andExpect(jsonPath("$.expectedTaskVersion").value(3))
        .andExpect(jsonPath("$.expectedEntryVersion").value(5))
        .andExpect(jsonPath("$.targetLane").value("SCHEDULED"))
        .andExpect(jsonPath("$.targetDate").value(targetDate.toString()))
        .andExpect(jsonPath("$.targetIndex").value(2))
        .andRespond(
            withSuccess(
                """
                {
                  "taskId":"%s","taskVersion":4,"warehouseId":"%s",
                  "externalTaskId":"%s","title":"Доставить бытовку в ремонт",
                  "unitNumber":"СПБ-001","description":"Доставить бытовку в ремонт",
                  "driverAudience":{"mode":"WAREHOUSE_DRIVERS","workerId":null,"workerName":null},
                  "status":"ACTIVE","plannedDurationMinutes":null,"deadlineAt":null,
                  "scheduledDate":"%s","lane":"SCHEDULED","priority":2,
                  "pinned":false,"doneAt":null,
                  "route":[{
                    "entryId":"%s","entryVersion":6,
                    "queueDefinitionId":"%s","workQueueId":"%s","queueName":"Водители",
                    "routeIndex":0,"queuePosition":2,"entryType":"REAL",
                    "status":"WAITING","taskText":"Доставить бытовку в ремонт",
                    "plannedDurationMinutes":null
                  }]
                }
                """
                    .formatted(
                        taskId,
                        warehouseId,
                        externalTaskId,
                        targetDate,
                        entryId,
                        queueDefinitionId,
                        queueId),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.DriverBoardTask moved =
        gateway.moveDriverTask(externalTaskId, 3, 5, "SCHEDULED", targetDate, 2);

    assertThat(moved.scheduledDate()).isEqualTo(targetDate);
    assertThat(moved.queuePosition()).isEqualTo(2);
    assertThat(moved.unitNumber()).isEqualTo("СПБ-001");
    server.verify();
  }

  @Test
  void cancelsCapitalDriverTaskThenReadsItsAuthoritativeCancelledSnapshot() {
    UUID externalTaskId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID queueDefinitionId = UUID.randomUUID();
    UUID queueId = UUID.randomUUID();
    server
        .expect(
            requestTo(
                "http://task-board.test/api/internal/task-board/v1/tasks/"
                    + externalTaskId
                    + "/cancel"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-task-board.logistics"))
        .andExpect(jsonPath("$.expectedTaskVersion").value(3))
        .andExpect(jsonPath("$.reason").value("Капитальный ремонт возвращён в отдельную очередь"))
        .andRespond(
            withSuccess(
                """
                {
                  "taskId":"%s","externalTaskId":"%s","taskVersion":4,
                  "status":"CANCELLED","cancelledAt":"2026-08-03T10:00:00Z"
                }
                """
                    .formatted(taskId, externalTaskId),
                MediaType.APPLICATION_JSON));
    server
        .expect(
            requestTo("http://task-board.test/api/internal/task-board/v1/tasks/" + externalTaskId))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer test-task-board.logistics"))
        .andRespond(
            withSuccess(
                """
                {
                  "taskId":"%s","taskVersion":4,"warehouseId":"%s",
                  "externalTaskId":"%s","title":"Переместить бытовку на производство",
                  "unitNumber":"СПБ-КАП","description":"Переместить бытовку на производство",
                  "driverAudience":{"mode":"WAREHOUSE_DRIVERS","workerId":null,"workerName":null},
                  "status":"CANCELLED","plannedDurationMinutes":null,"deadlineAt":null,
                  "scheduledDate":"2026-08-03","lane":"CURRENT","priority":2,
                  "pinned":false,"doneAt":null,
                  "route":[{
                    "entryId":"%s","entryVersion":6,
                    "queueDefinitionId":"%s","workQueueId":"%s","queueName":"Водители",
                    "routeIndex":0,"queuePosition":0,"entryType":"REAL",
                    "status":"CANCELLED","taskText":"Переместить бытовку на производство",
                    "plannedDurationMinutes":null
                  }]
                }
                """
                    .formatted(
                        taskId, warehouseId, externalTaskId, entryId, queueDefinitionId, queueId),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.DriverBoardTask cancelled =
        gateway.cancelDriverTask(externalTaskId, 3);

    assertThat(cancelled.status()).isEqualTo("CANCELLED");
    assertThat(cancelled.entryStatus()).isEqualTo("CANCELLED");
    assertThat(cancelled.taskVersion()).isEqualTo(4);
    server.verify();
  }

  @Test
  void invokesAtomicPreStartDriverCancellationWithTheExactTaskBoardScope() {
    UUID externalTaskId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    OffsetDateTime cancelledAt = OffsetDateTime.parse("2026-08-03T10:00:00Z");
    server
        .expect(
            requestTo(
                "http://task-board.test/api/internal/task-board/v1/tasks/"
                    + externalTaskId
                    + "/cancel-if-pre-start"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-task-board.logistics"))
        .andExpect(jsonPath("$.expectedTaskVersion").value(3))
        .andExpect(jsonPath("$.reason").value("inventory replacement compensation"))
        .andRespond(
            withSuccess(
                """
                {
                  "outcome":"CANCELLED","taskId":"%s","externalTaskId":"%s",
                  "taskVersion":4,"status":"CANCELLED",
                  "cancelledAt":"2026-08-03T10:00:00Z"
                }
                """
                    .formatted(taskId, externalTaskId),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.DriverTaskPreStartCancellation result =
        gateway.cancelDriverTaskIfPreStart(externalTaskId, 3, "inventory replacement compensation");

    assertThat(result.outcome())
        .isEqualTo(LogisticsDependencyGateway.DriverTaskPreStartCancellationOutcome.CANCELLED);
    assertThat(result.taskId()).isEqualTo(taskId);
    assertThat(result.taskVersion()).isEqualTo(4);
    assertThat(result.cancelledAt()).isEqualTo(cancelledAt);
    server.verify();
  }

  @Test
  void usesOnlyTheVersionedMaintenanceLogisticsRoutesForCapacityAndCapitalRepair() {
    UUID warehouseId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    server
        .expect(
            requestTo(
                "http://maintenance.test/api/internal/maintenance/v1/logistics/repair-places/"
                    + warehouseId))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer test-maintenance.logistics"))
        .andRespond(
            withSuccess(
                """
                {
                  "warehouseId":"%s","repairPlaceCount":3,"automaticRefillDelayMinutes":5,"reservedCount":0,
                  "occupiedCount":2,"readyToReleaseCount":1,"availableCount":1,
                  "overCapacity":false,"allocations":[{
                    "id":"%s","version":4,"warehouseId":"%s","repairId":"%s",
                    "rentalItemId":"%s","state":"READY_TO_RELEASE",
                    "repairStageName":"Электрика","repairStageState":"IN_PROGRESS",
                    "priority":2,
                    "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"
                  }]
                }
                """
                    .formatted(warehouseId, UUID.randomUUID(), warehouseId, repairId, cabinId),
                MediaType.APPLICATION_JSON));
    server
        .expect(
            requestTo(
                "http://maintenance.test/api/internal/maintenance/v1/logistics/repairs/capital/"
                    + repairId))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer test-maintenance.logistics"))
        .andRespond(
            withSuccess(
                """
                {
                  "repairId":"%s","rentalItemId":"%s","warehouseId":"%s",
                  "priority":1,
                  "complexity":{
                    "type":"CAPITAL","name":"Капитальный ремонт","color":"#AA1122",
                    "plannedMinutes":"510","forcedCapital":true
                  },
                  "version":7
                }
                """
                    .formatted(repairId, cabinId, warehouseId),
                MediaType.APPLICATION_JSON));

    assertThat(gateway.readRepairPlaces(warehouseId))
        .satisfies(
            places -> {
              assertThat(places.availableCount()).isOne();
              assertThat(places.automaticRefillDelayMinutes()).isEqualTo(5);
              assertThat(places.allocations())
                  .singleElement()
                  .satisfies(
                      allocation -> {
                        assertThat(allocation.priority()).isEqualTo(2);
                        assertThat(allocation.repairStageName()).isEqualTo("Электрика");
                        assertThat(allocation.repairStageState()).isEqualTo("IN_PROGRESS");
                      });
            });
    assertThat(gateway.readCapitalRepair(repairId))
        .satisfies(
            repair -> {
              assertThat(repair.rentalItemId()).isEqualTo(cabinId);
              assertThat(repair.complexity().forcedCapital()).isTrue();
            });
    server.verify();
  }

  @Test
  void readsTheCabinIdentityFromAnEnrichedRepairPlaceTransition() {
    UUID warehouseId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    server
        .expect(
            requestTo(
                "http://maintenance.test/api/internal/maintenance/v1/logistics/repair-places/"
                    + warehouseId
                    + "/allocations/"
                    + repairId
                    + "/reserve"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-maintenance.logistics"))
        .andExpect(header("Idempotency-Key", idempotencyKey.toString()))
        .andExpect(jsonPath("$.expectedVersion").value(0))
        .andRespond(
            withSuccess(
                """
                {
                  "id":"%s","version":0,"warehouseId":"%s","repairId":"%s",
                  "rentalItemId":"%s","state":"RESERVED",
                  "createdAt":"2026-08-01T00:00:00Z","updatedAt":"2026-08-01T00:00:00Z"
                }
                """
                    .formatted(UUID.randomUUID(), warehouseId, repairId, rentalItemId),
                MediaType.APPLICATION_JSON));

    LogisticsDependencyGateway.RepairPlaceAllocation allocation =
        gateway.transitionRepairPlace(idempotencyKey, warehouseId, repairId, 0, "reserve");

    assertThat(allocation.rentalItemId()).isEqualTo(rentalItemId);
    assertThat(allocation.state()).isEqualTo("RESERVED");
    server.verify();
  }

  @Test
  void usesFourExactWarehouseLifecycleScopesAndVersionedRoutes() {
    UUID warehouseId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    OffsetDateTime at = OffsetDateTime.parse("2026-09-01T00:30:00Z");

    server
        .expect(
            requestTo(
                "http://warehouse.test/api/internal/warehouse/v1/warehouses/"
                    + warehouseId
                    + "/admission?direction=OUTGOING"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer test-warehouse.lifecycle.read"))
        .andRespond(
            withSuccess(
                """
                {"warehouseId":"%s","warehouseVersion":7,"lifecycleState":"DRAINING",
                 "direction":"OUTGOING","admitted":true}
                """
                    .formatted(warehouseId),
                MediaType.APPLICATION_JSON));
    server
        .expect(
            requestTo(
                "http://warehouse.test/api/internal/warehouse/v1/warehouses/"
                    + warehouseId
                    + "/time-zone?at="
                    + at))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer test-warehouse.timezone.read"))
        .andRespond(
            withSuccess(
                """
                {"warehouseId":"%s","timeZone":"Europe/Samara",
                 "effectiveFrom":"2026-09-01T00:00:00Z"}
                """
                    .formatted(warehouseId),
                MediaType.APPLICATION_JSON));
    server
        .expect(
            requestTo(
                "http://warehouse.test/api/internal/warehouse/v1/warehouses/"
                    + warehouseId
                    + "/operation-marks"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-warehouse.operation.mark"))
        .andExpect(jsonPath("$.operationId").value(operationId.toString()))
        .andExpect(jsonPath("$.occurredAt").value("2026-09-01T00:30:00Z"))
        .andRespond(withSuccess());
    server
        .expect(
            requestTo(
                "http://warehouse.test/api/internal/warehouse/v1/lifecycle/readiness-work?limit=100"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer test-warehouse.lifecycle.read"))
        .andRespond(
            withSuccess(
                """
                {"items":[{"warehouseId":"%s","warehouseVersion":7,
                  "lifecycleState":"DRAINING"}],"nextAfter":null}
                """
                    .formatted(warehouseId),
                MediaType.APPLICATION_JSON));
    server
        .expect(
            requestTo(
                "http://warehouse.test/api/internal/warehouse/v1/warehouses/"
                    + warehouseId
                    + "/lifecycle-readiness"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-warehouse.lifecycle.confirm"))
        .andExpect(jsonPath("$.expectedVersion").value(7))
        .andRespond(
            withSuccess(
                """
                {"warehouseId":"%s","warehouseVersion":8,"lifecycleState":"DRAINING",
                 "readinessOwner":"LOGISTICS","confirmedAt":"2026-09-01T00:31:00Z"}
                """
                    .formatted(warehouseId),
                MediaType.APPLICATION_JSON));

    assertThat(
            gateway.warehouseAdmission(
                warehouseId, LogisticsDependencyGateway.WarehouseOperationDirection.OUTGOING))
        .extracting(LogisticsDependencyGateway.WarehouseOperationAdmission::warehouseVersion)
        .isEqualTo(7L);
    assertThat(gateway.warehouseTimeZoneAt(warehouseId, at).timeZone()).isEqualTo("Europe/Samara");
    gateway.markWarehouseOperation(warehouseId, operationId, at);
    assertThat(gateway.warehouseLifecycleReadinessWork(null, 100).items())
        .singleElement()
        .extracting(LogisticsDependencyGateway.WarehouseLifecycleReadinessWork::warehouseId)
        .isEqualTo(warehouseId);
    assertThat(gateway.confirmWarehouseLifecycleReadiness(warehouseId, 7).warehouseVersion())
        .isEqualTo(8);
    server.verify();
  }

  @Test
  void rejectsInconsistentWarehouseAdmissionTruth() {
    UUID warehouseId = UUID.randomUUID();
    server
        .expect(
            requestTo(
                "http://warehouse.test/api/internal/warehouse/v1/warehouses/"
                    + warehouseId
                    + "/admission?direction=INCOMING"))
        .andRespond(
            withSuccess(
                """
                {"warehouseId":"%s","warehouseVersion":2,"lifecycleState":"DRAINING",
                 "direction":"INCOMING","admitted":true}
                """
                    .formatted(warehouseId),
                MediaType.APPLICATION_JSON));

    assertThatThrownBy(
            () ->
                gateway.warehouseAdmission(
                    warehouseId, LogisticsDependencyGateway.WarehouseOperationDirection.INCOMING))
        .isInstanceOf(LogisticsDependencyException.class)
        .satisfies(
            failure ->
                assertThat(((LogisticsDependencyException) failure).kind())
                    .isEqualTo(LogisticsDependencyException.FailureKind.CONFIGURATION));
    server.verify();
  }
}
