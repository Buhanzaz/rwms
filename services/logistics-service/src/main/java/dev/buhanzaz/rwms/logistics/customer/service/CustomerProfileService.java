package dev.buhanzaz.rwms.logistics.customer.service;

import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerProfileRequest;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerProfileResponse;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerEntityType;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerProfile;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerProfileRepository;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerAuthorizer;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.NewClientInput;
import dev.buhanzaz.rwms.logistics.order.domain.ClientType;
import dev.buhanzaz.rwms.logistics.order.service.OrderClientService;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns the one-time CustomerApp profile to rental-client creation transaction. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerProfileService {
  private final CustomerProfileRepository profiles;
  private final OrderClientService clients;
  private final CustomerAuthorizer access;
  private final LogisticsTransactionLock transactionLock;

  /** Returns the current subject's profile or a no-disclosure 404. */
  public CustomerProfileResponse get(CustomerIdentity identity) {
    return response(required(identity));
  }

  /** Creates exactly one profile and its existing logistics-owned client projection. */
  @Transactional
  public CustomerProfileResponse create(
      CustomerIdentity identity, CustomerProfileRequest request) {
    transactionLock.acquire("customer-profile:" + identity.subjectId());
    if (profiles.findByAuthSubjectId(identity.subjectId()).isPresent()) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT, "CUSTOMER_PROFILE_EXISTS", "Профиль клиента уже создан");
    }
    String displayName = displayName(request);
    String contactPerson = contactPerson(request);
    NewClientInput input =
        new NewClientInput(
            request.entityType() == CustomerEntityType.LEGAL
                ? ClientType.LEGAL_ENTITY
                : ClientType.INDIVIDUAL,
            displayName,
            request.phone(),
            contactPerson,
            request.email(),
            request.additionalInfo(),
            "CustomerApp");
    var created =
        clients.createForOrder(
            access.orderActor(identity, null), deterministic("customer-profile:" + identity.subjectId()), input);
    CustomerProfile profile =
        profiles.saveAndFlush(
            CustomerProfile.create(
                identity.subjectId(),
                created.client().getId(),
                request.entityType(),
                request.firstName(),
                request.lastName(),
                request.companyName(),
                request.phone(),
                request.email(),
                request.additionalInfo()));
    return response(profile);
  }

  /** Resolves the profile entity only for internal customer orchestration. */
  public CustomerProfile required(CustomerIdentity identity) {
    return profiles
        .findByAuthSubjectId(identity.subjectId())
        .orElseThrow(
            () ->
                new OrderProblemException(
                    HttpStatus.NOT_FOUND,
                    "CUSTOMER_PROFILE_REQUIRED",
                    "Сначала заполните профиль клиента"));
  }

  private static CustomerProfileResponse response(CustomerProfile profile) {
    return new CustomerProfileResponse(
        profile.getId(),
        profile.getVersion(),
        profile.getEntityType(),
        profile.getFirstName(),
        profile.getLastName(),
        profile.getCompanyName(),
        profile.getPhone(),
        profile.getEmail(),
        profile.getAdditionalInfo());
  }

  private static String displayName(CustomerProfileRequest request) {
    if (request.entityType() == CustomerEntityType.LEGAL) return request.companyName();
    return request.firstName().trim() + " " + request.lastName().trim();
  }

  private static String contactPerson(CustomerProfileRequest request) {
    if (request.entityType() != CustomerEntityType.LEGAL) return null;
    String first = request.firstName() == null ? "" : request.firstName().trim();
    String last = request.lastName() == null ? "" : request.lastName().trim();
    String contact = (first + " " + last).trim();
    return contact.isEmpty() ? request.companyName() : contact;
  }

  private static UUID deterministic(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }
}
