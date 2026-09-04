package dev.buhanzaz.rwms.logistics.domain;

/**
 * Customer-facing commercial purpose of a shipment document.
 *
 * <p>This purpose is independent from the physical document type: warehouse-to-warehouse work
 * remains {@link LogisticsDocumentType#TRANSFER}, while every customer delivery remains a
 * {@link LogisticsDocumentType#SHIPMENT}.
 */
public enum CustomerDeliveryPurpose {
  RENTAL_DELIVERY,
  SALE_DELIVERY,
  CUSTOMER_RELOCATION
}
