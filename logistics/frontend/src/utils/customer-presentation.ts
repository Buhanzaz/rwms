import type { components } from '../api/schema';
import type { CustomerDeliveryPurpose } from '../domain/types';

/** Canonical legal type values exposed by the logistics request contract. */
export type CustomerLegalType = NonNullable<
  components['schemas']['LogisticsRequestRead']['client_type']
>;

const CUSTOMER_LEGAL_TYPE_LABELS: Readonly<Record<CustomerLegalType, string>> = {
  INDIVIDUAL: 'Физическое лицо',
  SOLE_PROPRIETOR: 'Индивидуальный предприниматель',
  LEGAL_ENTITY: 'Юридическое лицо',
};

/** Accepts only canonical contract values and never guesses a type from customer text. */
export function normalizeCustomerLegalType(value: unknown): CustomerLegalType | null {
  if (value === 'INDIVIDUAL' || value === 'SOLE_PROPRIETOR' || value === 'LEGAL_ENTITY') {
    return value;
  }
  return null;
}

/** Returns one shared dispatcher-facing label for a canonical legal type or missing data. */
export function customerLegalTypeLabel(value: unknown): string {
  const legalType = normalizeCustomerLegalType(value);
  return legalType ? CUSTOMER_LEGAL_TYPE_LABELS[legalType] : 'Тип клиента не указан';
}

/** Reads the explicit transport field without deriving legal type from any other request field. */
export function customerLegalTypeFromRequest(request: unknown): CustomerLegalType | null {
  if (!request || typeof request !== 'object') return null;
  return normalizeCustomerLegalType((request as Record<string, unknown>).client_type);
}

const CUSTOMER_DELIVERY_PURPOSE_LABELS: Readonly<Record<CustomerDeliveryPurpose, string>> = {
  RENTAL_DELIVERY: 'Доставка в аренду',
  SALE_DELIVERY: 'Доставка продажи',
  CUSTOMER_RELOCATION: 'Перемещение клиента',
};

/** Keeps a commercial purpose distinct from the route's physical delivery/pickup direction. */
export function customerDeliveryPurposeLabel(value: unknown): string {
  if (value === 'RENTAL_DELIVERY' || value === 'SALE_DELIVERY' || value === 'CUSTOMER_RELOCATION') {
    return CUSTOMER_DELIVERY_PURPOSE_LABELS[value];
  }
  return 'Доставка';
}

/** Reads the owner-supplied commercial delivery purpose without deriving it from route direction. */
export function customerDeliveryPurposeFromRequest(request: unknown): CustomerDeliveryPurpose | null {
  if (!request || typeof request !== 'object') return null;
  const value = (request as Record<string, unknown>).customer_delivery_purpose;
  return value === 'RENTAL_DELIVERY' || value === 'SALE_DELIVERY' || value === 'CUSTOMER_RELOCATION'
    ? value
    : null;
}
