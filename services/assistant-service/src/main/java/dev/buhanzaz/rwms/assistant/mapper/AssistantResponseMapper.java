package dev.buhanzaz.rwms.assistant.mapper;

import dev.buhanzaz.rwms.assistant.api.AssistantApiModels;
import dev.buhanzaz.rwms.assistant.domain.AssistantConversation;
import dev.buhanzaz.rwms.assistant.domain.AssistantMessage;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Maps service-owned conversation and message records to public assistant response models without exposing tool persistence internals. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AssistantResponseMapper {
  AssistantApiModels.ConversationResponse toConversationResponse(AssistantConversation source);

  @Mapping(target = "role", expression = "java(source.getRole().name())")
  @Mapping(target = "searchNotices", expression = "java(java.util.List.of())")
  AssistantApiModels.MessageResponse toMessageResponse(AssistantMessage source);
}
