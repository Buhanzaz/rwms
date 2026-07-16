package dev.buhanzaz.rwms.architecture.fixture.mapper;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.mapstruct.MappingTarget;
import org.mapstruct.Mapper;

@Mapper
public interface UnsafeMutationMapper {
  UnsafeEntity toEntity(UnsafeRequest request);

  void updateEntity(UnsafeRequest request, @MappingTarget UnsafeEntity entity);

  record UnsafeRequest(String id) {}

  @Entity
  class UnsafeEntity {
    @Id String id;
  }
}
