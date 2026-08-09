package dev.buhanzaz.rwms.logistics.eventing;

import dev.buhanzaz.rwms.logistics.eventing.inbound.LogisticsInboundTransportTopics;
import java.util.List;

/**
 * Canonical ordered logistics output topics and the separate sanitized-DLT binding set.
 *
 * <p>The three document-family topics are checked against their domain aggregate mapping by the
 * transport configuration test. The rental-inquiry topic is the exact AsyncAPI address. Primary
 * destinations are intentionally kept separate from technical DLT bindings so the platform
 * publisher allow-list never treats a DLT as a business aggregate output.
 */
public final class LogisticsTransportTopics {
  /** Canonical return aggregate-family topic. */
  public static final String RETURN = "rwms.logistics.return.v1";

  /** Canonical shipment aggregate-family topic. */
  public static final String SHIPMENT = "rwms.logistics.shipment.v1";

  /** Canonical transfer aggregate-family topic. */
  public static final String TRANSFER = "rwms.logistics.transfer.v1";

  /** Canonical rental-inquiry event-family topic from the logistics AsyncAPI contract. */
  public static final String RENTAL_INQUIRY = "rwms.logistics.rental-inquiry.events.v1";

  /** Sanitized return failure-metadata topic. */
  public static final String RETURN_DLT = RETURN + ".logistics-service.dlt";

  /** Sanitized shipment failure-metadata topic. */
  public static final String SHIPMENT_DLT = SHIPMENT + ".logistics-service.dlt";

  /** Sanitized transfer failure-metadata topic. */
  public static final String TRANSFER_DLT = TRANSFER + ".logistics-service.dlt";

  /** Sanitized inbound failure-metadata topic. */
  public static final String INBOUND_DLT = LogisticsInboundTransportTopics.SANITIZED_DLT;

  /** Exact ordered business-output allow-list used by configuration and startup validation. */
  public static final List<String> PRIMARY_OUTPUTS =
      List.of(RETURN, SHIPMENT, TRANSFER, RENTAL_INQUIRY);

  /** Exact technical outputs that contain sanitized failure metadata rather than business facts. */
  public static final List<String> SANITIZED_DLT_OUTPUTS =
      List.of(RETURN_DLT, SHIPMENT_DLT, TRANSFER_DLT, INBOUND_DLT);

  /** Prevents construction of the transport authority. */
  private LogisticsTransportTopics() {}
}
