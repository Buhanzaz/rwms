package dev.buhanzaz.rwms.logistics.driver.api;

import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.ExpiredTripNoticeResponse;
import dev.buhanzaz.rwms.logistics.driver.service.DriverTaskService;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Limited warehouse-wide trip notices for the isolated rental-manager client. */
@RestController
@RequiredArgsConstructor
public class RentalExpiredTripController {
  private final OrderAuthorizer access;
  private final DriverTaskService tasks;

  @GetMapping("/api/logistics/v1/rental-expired-trips")
  public List<ExpiredTripNoticeResponse> list(@AuthenticationPrincipal Jwt jwt) {
    return tasks.expiredTripsForManager(access.readActor(jwt));
  }
}
