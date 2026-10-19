package dev.buhanzaz.rwms.architecture.fixture.mapper;

import org.mapstruct.Mapper;

@Mapper
public interface SafeReadMapper {
  SafeDto toDto(SafeProjection source);

  record SafeProjection(String id) {}

  record SafeDto(String id) {}
}
