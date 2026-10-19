package dev.buhanzaz.rwms.logistics.customer.service;

import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerProfileRequest;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerProfileAvatarResponse;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerProfileAvatarUploadScope;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerProfileResponse;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.PrepareCustomerProfileAvatarUploadRequest;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.SetCustomerProfileAvatarRequest;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.UpdateCustomerProfileRequest;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerEntityType;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerProfile;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerProfileRepository;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerAuthorizer;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
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

/** Owns the CustomerApp profile, rental-client projection and subject-bound avatar scope. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerProfileService {
  private final CustomerProfileRepository profiles;
  private final OrderClientService clients;
  private final CustomerAuthorizer access;
  private final LogisticsTransactionLock transactionLock;
  private final CustomerWarehouseService warehouses;
  private final LogisticsDependencyGateway dependencies;

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
            access.orderActor(identity, null),
            deterministic("customer-profile:" + identity.subjectId()),
            input);
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

  /**
   * Replaces mutable profile facts and the logistics client search projection in one local
   * transaction. The entity kind, auth subject and client identity cannot change.
   */
  @Transactional
  public CustomerProfileResponse update(
      CustomerIdentity identity, UpdateCustomerProfileRequest request) {
    transactionLock.acquire("customer-profile:" + identity.subjectId());
    CustomerProfile profile = required(identity);
    try {
      profile.updateDetails(
          request.expectedVersion(),
          request.firstName(),
          request.lastName(),
          request.companyName(),
          request.phone(),
          request.email(),
          request.additionalInfo());
    } catch (IllegalStateException exception) {
      throw versionConflict();
    }
    clients.updateCustomerProfile(
        profile.getClientId(),
        clientType(profile.getEntityType()),
        profile.displayName(),
        profile.getPhone(),
        profile.contactPerson(),
        profile.getEmail(),
        profile.getAdditionalInfo());
    return response(profiles.saveAndFlush(profile));
  }

  /**
   * Establishes the immutable warehouse/subject owner proof used by media-service. A successful
   * remote proof with a rolled-back local transaction is safe because the deterministic proof
   * event is replayed on retry.
   */
  @Transactional
  public CustomerProfileAvatarUploadScope prepareAvatarUpload(
      CustomerIdentity identity, PrepareCustomerProfileAvatarUploadRequest request) {
    transactionLock.acquire("customer-profile:" + identity.subjectId());
    CustomerProfile profile = required(identity);
    warehouses.required(request.warehouseId());
    if (profile.getAvatarWarehouseId() != null
        && !profile.getAvatarWarehouseId().equals(request.warehouseId())) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CUSTOMER_PROFILE_AVATAR_SCOPE_CONFLICT",
          "Склад профиля для фотографии уже выбран");
    }
    try {
      if (profile.prepareAvatarScope(request.expectedVersion(), request.warehouseId())) {
        profiles.saveAndFlush(profile);
      }
    } catch (IllegalStateException exception) {
      throw versionConflict();
    }
    UUID proofEventId = deterministic("customer-profile-avatar-proof:" + profile.getId());
    dependencies.upsertCustomerProfileMediaOwnerProof(
        profile.getId(),
        profile.getAvatarWarehouseId(),
        identity.subjectId(),
        proofEventId);
    return new CustomerProfileAvatarUploadScope(
        profile.getVersion(),
        "LOGISTICS_CUSTOMER_PROFILE",
        profile.getId(),
        profile.getAvatarWarehouseId(),
        "PROFILE_AVATAR");
  }

  /** Validates and binds one exact READY avatar generation under the profile version fence. */
  @Transactional
  public CustomerProfileResponse setAvatar(
      CustomerIdentity identity, SetCustomerProfileAvatarRequest request) {
    transactionLock.acquire("customer-profile:" + identity.subjectId());
    CustomerProfile profile = required(identity);
    if (profile.getAvatarWarehouseId() == null) {
      throw new OrderProblemException(
          HttpStatus.CONFLICT,
          "CUSTOMER_PROFILE_AVATAR_SCOPE_REQUIRED",
          "Сначала выберите склад для загрузки фотографии");
    }
    try {
      profile.bindAvatar(request.expectedVersion(), request.mediaId(), request.generation());
    } catch (IllegalStateException exception) {
      throw versionConflict();
    }
    dependencies.validateCustomerProfileMediaReference(
        profile.getId(),
        profile.getAvatarWarehouseId(),
        identity.subjectId(),
        new LogisticsDependencyGateway.MediaReference(request.mediaId(), request.generation()));
    return response(profiles.saveAndFlush(profile));
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
        profile.getAdditionalInfo(),
        avatar(profile));
  }

  private static CustomerProfileAvatarResponse avatar(CustomerProfile profile) {
    if (profile.getAvatarMediaId() == null
        || profile.getAvatarGeneration() == null
        || profile.getAvatarWarehouseId() == null) {
      return null;
    }
    String query =
        "?ownerType=LOGISTICS_CUSTOMER_PROFILE&ownerId="
            + profile.getId()
            + "&warehouseId="
            + profile.getAvatarWarehouseId()
            + "&context=PROFILE_AVATAR&generation="
            + profile.getAvatarGeneration();
    String base = "/api/media/v1/assets/" + profile.getAvatarMediaId() + "/variants/";
    return new CustomerProfileAvatarResponse(
        profile.getAvatarMediaId(),
        profile.getAvatarGeneration(),
        profile.getAvatarWarehouseId(),
        base + "SMALL/content" + query,
        base + "LARGE/content" + query);
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

  private static ClientType clientType(CustomerEntityType type) {
    return type == CustomerEntityType.LEGAL ? ClientType.LEGAL_ENTITY : ClientType.INDIVIDUAL;
  }

  private static OrderProblemException versionConflict() {
    return new OrderProblemException(
        HttpStatus.CONFLICT,
        "CUSTOMER_PROFILE_VERSION_CONFLICT",
        "Профиль изменился. Обновите данные и повторите действие");
  }

  private static UUID deterministic(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }
}
