package dev.buhanzaz.rwms.auth.mapper;

import dev.buhanzaz.rwms.auth.api.AdminUserResponse;
import dev.buhanzaz.rwms.auth.api.ActorDisplayResponse;
import dev.buhanzaz.rwms.auth.api.CurrentUserResponse;
import dev.buhanzaz.rwms.auth.api.EffectiveWarehouseAccessDto;
import dev.buhanzaz.rwms.auth.api.WarehouseAccessDto;
import dev.buhanzaz.rwms.auth.api.WorkerCredentialResponse;
import dev.buhanzaz.rwms.auth.api.WorkerCredentialResponse.WorkerCredentialStatus;
import dev.buhanzaz.rwms.auth.domain.AuthSubject;
import dev.buhanzaz.rwms.auth.eventing.AuthSubjectProfileStore.Profile;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Maps authorization aggregates and their read-side profile data to transport-safe API responses.
 *
 * <p>Credential hashes and other authentication secrets are intentionally absent from every
 * mapping defined here.
 */
@Mapper
public interface AuthResponseMapper {

    /**
     * Maps an interactive user and its warehouse grants to the administrative representation.
     *
     * @param subject authorization aggregate
     * @param profile read-side profile data for the aggregate
     * @param warehouseAccesses configured warehouse grants
     * @return transport-safe administrative user projection
     */
    @Mapping(target = "id", source = "subject.id")
    @Mapping(target = "version", source = "subject.version")
    @Mapping(target = "username", source = "profile.username")
    @Mapping(target = "firstName", source = "profile.firstName")
    @Mapping(target = "lastName", source = "profile.lastName")
    @Mapping(target = "email", source = "profile.email")
    @Mapping(target = "timeZoneId", source = "profile.timeZoneId")
    @Mapping(target = "active", source = "subject.active")
    @Mapping(target = "globalRole", source = "subject.globalRole")
    @Mapping(target = "mobileAppAccess", source = "subject.mobileAppAccess")
    @Mapping(target = "rentalAccess", source = "subject.rentalAccess")
    @Mapping(target = "warehouseAccesses", source = "warehouseAccesses")
    AdminUserResponse toAdmin(
            AuthSubject subject, Profile profile, List<WarehouseAccessDto> warehouseAccesses);

    /**
     * Maps the authenticated subject to the representation returned by the current-user API.
     *
     * @param subject authorization aggregate
     * @param profile read-side profile data for the aggregate
     * @param displayName derived human-readable name
     * @param warehouseAccessAll whether the role has unrestricted warehouse access
     * @param warehouseAccesses effective warehouse grants
     * @return current-user projection
     */
    @Mapping(target = "id", source = "subject.id")
    @Mapping(target = "username", source = "profile.username")
    @Mapping(target = "displayName", source = "displayName")
    @Mapping(target = "firstName", source = "profile.firstName")
    @Mapping(target = "lastName", source = "profile.lastName")
    @Mapping(target = "email", source = "profile.email")
    @Mapping(target = "principalType", source = "subject.principalType")
    @Mapping(target = "globalRole", source = "subject.globalRole")
    @Mapping(target = "rentalAccess", source = "subject.rentalAccess")
    @Mapping(target = "warehouseAccessAll", source = "warehouseAccessAll")
    @Mapping(target = "warehouseAccesses", source = "warehouseAccesses")
    CurrentUserResponse toCurrent(
            AuthSubject subject,
            Profile profile,
            String displayName,
            boolean warehouseAccessAll,
            List<EffectiveWarehouseAccessDto> warehouseAccesses);

    /**
     * Maps a subject to the compact, non-secret display data used to identify actors.
     *
     * @param subject authorization aggregate
     * @param profile read-side profile data for the aggregate
     * @return compact actor display projection
     */
    @Mapping(target = "subjectId", source = "subject.id")
    @Mapping(target = "principalType", source = "subject.principalType")
    @Mapping(target = "globalRole", source = "subject.globalRole")
    @Mapping(target = "username", source = "profile.username")
    @Mapping(target = "firstName", source = "profile.firstName")
    @Mapping(target = "lastName", source = "profile.lastName")
    @Mapping(target = "email", source = "profile.email")
    ActorDisplayResponse toActorDisplay(AuthSubject subject, Profile profile);

    /**
     * Maps worker credential state to a response that never contains the supplied password.
     *
     * @param workerId external worker identifier
     * @param warehouseId bound warehouse identifier
     * @param appLogin worker-application login
     * @param status credential lifecycle status
     * @return non-secret worker credential projection
     */
    WorkerCredentialResponse toWorker(
            String workerId,
            String warehouseId,
            String appLogin,
            WorkerCredentialStatus status);
}
