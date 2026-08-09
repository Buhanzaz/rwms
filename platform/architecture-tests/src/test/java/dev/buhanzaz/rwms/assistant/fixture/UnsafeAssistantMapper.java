package dev.buhanzaz.rwms.assistant.fixture;

import org.mapstruct.Mapper;

/** Unsafe MapStruct fixture placed outside an explicit mapper or mapping package. */
@Mapper
public interface UnsafeAssistantMapper {
  Target toTarget(Source source);

  /** Source value used by the mapper-placement fixture. */
  record Source(String id) {}

  /** Target value used by the mapper-placement fixture. */
  record Target(String id) {}
}
