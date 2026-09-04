package dev.buhanzaz.rwms.logistics.inquiry.service;


import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.CabinSelectionRequest;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquiry;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquirySelectionReceiptState;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquiryState;
import dev.buhanzaz.rwms.logistics.inquiry.repository.RentalInquiryRepository;
import dev.buhanzaz.rwms.logistics.inquiry.repository.RentalInquirySelectionReceiptRepository;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Verifies deterministic receipt preparation for settings-derived cabin-selection TTLs. */
class RentalInquiryCabinSelectionServiceTest {
  private static final UUID MANAGER = UUID.fromString("00000000-0000-4000-8000-000000008101");
  private static final UUID INQUIRY = UUID.fromString("00000000-0000-4000-8000-000000008102");
  private static final UUID WAREHOUSE = UUID.fromString("00000000-0000-4000-8000-000000008103");
  private static final UUID CABIN = UUID.fromString("00000000-0000-4000-8000-000000008104");
  private static final Instant NOW = Instant.parse("2026-08-09T10:15:30.123456Z");

  @Test
  void prepareFreezesTheExactConfiguredExpiryAndRequestBytes() throws Exception {
    RentalInquiryRepository inquiries = mock(RentalInquiryRepository.class);
    RentalInquirySelectionReceiptRepository receipts =
        mock(RentalInquirySelectionReceiptRepository.class);
    RentalSettingsService settings = mock(RentalSettingsService.class);
    OrderAuthorizer access = mock(OrderAuthorizer.class);
    var json = JsonMapper.builder().findAndAddModules().build();
    RentalInquiryCabinSelectionStore store =
        new RentalInquiryCabinSelectionStore(
            inquiries, receipts, settings, access, json, Clock.fixed(NOW, ZoneOffset.UTC));
    OrderActor actor =
        new OrderActor(
            MANAGER,
            "RENTAL_MANAGER",
            "Менеджер",
            Set.of(WAREHOUSE),
            Set.of(WAREHOUSE),
            false,
            false,
            true,
            true);
    RentalInquiry inquiry = mock(RentalInquiry.class);
    when(inquiries.findForUpdate(INQUIRY)).thenReturn(Optional.of(inquiry));
    when(inquiry.getId()).thenReturn(INQUIRY);
    when(inquiry.getManagerId()).thenReturn(MANAGER);
    when(inquiry.getState()).thenReturn(RentalInquiryState.ACTIVE);
    when(inquiry.getWarehouseId()).thenReturn(WAREHOUSE);
    when(receipts.findByPublicKeyForUpdate(MANAGER, INQUIRY))
        .thenReturn(Optional.empty());
    when(receipts.findByInquiryAndStateForUpdate(
            INQUIRY, RentalInquirySelectionReceiptState.PREPARED))
        .thenReturn(Optional.empty());
    when(receipts.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(settings.chatSelectionHoldMinutes(actor)).thenReturn(17);

    var result =
        store.prepare(
            actor, INQUIRY, INQUIRY, new CabinSelectionRequest(WAREHOUSE, List.of(CABIN)));

    OffsetDateTime expectedExpiry = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusMinutes(17);
    assertThat(result.prepared().commandExpiresAt()).isEqualTo(expectedExpiry);
    assertThat(result.prepared().expiresAt()).isEqualTo(expectedExpiry);
    JsonNode exact = json.readTree(result.prepared().exactRequestBody());
    assertThat(exact.get("expiresAt").stringValue()).isEqualTo(expectedExpiry.toString());
    assertThat(exact.at("/rentalItemIds/0").stringValue()).isEqualTo(CABIN.toString());
    assertThat(exact.get("actorSubjectId").stringValue()).isEqualTo(MANAGER.toString());
    verify(access).requireWarehouseEdit(actor, WAREHOUSE);
  }
}
