package dev.buhanzaz.rwms.logistics.order.service;

import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderDetailResponse;

/**
 * Internal result passed from a rental-order command owner to the compatibility facade.
 *
 * <p>It keeps replay information local to the order package so collaborators do not depend on the
 * outer public boundary's nested response records.
 */
record RentalOrderCommandOutcome(OrderDetailResponse response, boolean replayed) {}
