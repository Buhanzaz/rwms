package dev.buhanzaz.rwms.logistics.customer.api;

import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCabinPage;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerWarehouseResponse;

import dev.buhanzaz.rwms.logistics.customer.service.CustomerCabinCatalogService;
import dev.buhanzaz.rwms.logistics.customer.service.CustomerWarehouseService;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.CabinFacetsResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anonymous browsing of current customer-visible warehouses, cabin facts and gallery derivatives.
 * Every operation is a read; customer identities, carts and booking commands stay authenticated.
 */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/logistics/public/v1/catalog/warehouses")
public class PublicCustomerCatalogController {
  private final CustomerWarehouseService warehouses;
  private final CustomerCabinCatalogService catalog;

  /** Lists the same active routable warehouses offered to signed-in customers. */
  @GetMapping
  public ResponseEntity<List<CustomerWarehouseResponse>> warehouses() {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(warehouses.list());
  }

  /** Reads exact available facet values without creating an inquiry or retaining anyone's hold. */
  @GetMapping("/{warehouseId}/facets")
  public ResponseEntity<CabinFacetsResponse> facets(@PathVariable UUID warehouseId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(catalog.publicFacets(warehouseId));
  }

  /** Returns a bounded filtered page with live prices and anonymous, scope-checked photo URLs. */
  @GetMapping("/{warehouseId}/cabins")
  public ResponseEntity<CustomerCabinPage> cabins(
      @PathVariable UUID warehouseId,
      @RequestParam(required = false) @Size(max = 255) String query,
      @RequestParam(required = false) @Size(max = 255) String cabinType,
      @RequestParam(required = false) @Size(max = 255) String finish,
      @RequestParam(required = false) @Size(max = 255) String dimensions,
      @RequestParam(required = false) @Size(max = 255) String category,
      @RequestParam(required = false) Boolean linoleum,
      @RequestParam(required = false) @Size(max = 20)
          List<@Size(min = 1, max = 255) String> characteristics,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            catalog.publicPage(
                warehouseId,
                query,
                cabinType,
                finish,
                dimensions,
                category,
                linoleum,
                characteristics,
                page,
                size));
  }

  /** Streams only a current SMALL/LARGE gallery derivative for a currently bookable cabin. */
  @GetMapping("/{warehouseId}/cabins/{cabinId}/photos/{mediaId}")
  public ResponseEntity<byte[]> photo(
      @PathVariable UUID warehouseId,
      @PathVariable UUID cabinId,
      @PathVariable UUID mediaId,
      @RequestParam @Min(1) long generation,
      @RequestParam String variant) {
    var content = catalog.publicMedia(warehouseId, cabinId, mediaId, generation, variant);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .contentType(MediaType.parseMediaType(content.contentType()))
        .contentLength(content.bytes().length)
        .body(content.bytes());
  }
}
