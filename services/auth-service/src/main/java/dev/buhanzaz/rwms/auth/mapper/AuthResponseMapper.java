package dev.buhanzaz.rwms.auth.mapper;

import dev.buhanzaz.rwms.auth.api.AdminUserResponse;
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

@Mapper
public interface AuthResponseMapper {

    @Mapping(target = "id", source = "subject.id")
    @Mapping(target = "version", source = "subject.version")
    @Mapping(target = "username", source = "profile.username")
    @Mapping(target = "firstName", source = "profile.firstName")
    @Mapping(target = "lastName", source = "profile.lastName")
    @Mapping(target = "email", source = "profile.email")
    @Mapping(target = "timeZoneId", source = "profile.timeZoneId")
    @Mapping(target = "active", source = "subject.active")
    @Mapping(target = "globalRole", source = "subject.globalRole")
    @Mapping(target = "warehouseAccesses", source = "warehouseAccesses")
    AdminUserResponse toAdmin(
            AuthSubject subject, Profile profile, List<WarehouseAccessDto> warehouseAccesses);

    @Mapping(target = "id", source = "subject.id")
    @Mapping(target = "username", source = "profile.username")
    @Mapping(target = "displayName", source = "displayName")
    @Mapping(target = "firstName", source = "profile.firstName")
    @Mapping(target = "lastName", source = "profile.lastName")
    @Mapping(target = "email", source = "profile.email")
    @Mapping(target = "principalType", source = "subject.principalType")
    @Mapping(target = "globalRole", source = "subject.globalRole")
    @Mapping(target = "warehouseAccessAll", source = "warehouseAccessAll")
    @Mapping(target = "warehouseAccesses", source = "warehouseAccesses")
    CurrentUserResponse toCurrent(
            AuthSubject subject,
            Profile profile,
            String displayName,
            boolean warehouseAccessAll,
            List<EffectiveWarehouseAccessDto> warehouseAccesses);

    WorkerCredentialResponse toWorker(
            String workerId,
            String warehouseId,
            String appLogin,
            WorkerCredentialStatus status);
}
