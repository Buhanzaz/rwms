package dev.buhanzaz.rwms.warehouse.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Atomic full replacement of one served warehouse's support-link collection.
 *
 * @param expectedVersion served warehouse aggregate version fence
 * @param links complete replacement collection
 */
public record ReplaceWarehouseSupportLinksRequest(
    @NotNull @Min(0) Long expectedVersion,
    @NotNull List<@Valid WarehouseSupportLinkInput> links) {

  /** Copies the mutable transport list before application processing. */
  public ReplaceWarehouseSupportLinksRequest {
    links = links == null ? null : List.copyOf(links);
  }
}
