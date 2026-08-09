package dev.buhanzaz.rwms.assistant.service;

import dev.buhanzaz.rwms.assistant.api.AssistantApiModels;
import dev.buhanzaz.rwms.assistant.domain.AssistantClarificationKind;
import dev.buhanzaz.rwms.assistant.domain.AssistantClarificationQuestion;
import dev.buhanzaz.rwms.assistant.domain.AssistantClarificationStatus;
import dev.buhanzaz.rwms.assistant.repository.AssistantClarificationQuestionRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** Owns durable independent question branches and exact button-answer transitions. */
@Service
public class AssistantClarificationService {
  private static final int MAX_QUESTIONS_PER_TOOL_CALL = 5;
  private static final int MAX_OPTIONS_PER_QUESTION = 30;

  private final AssistantClarificationQuestionRepository questions;

  public AssistantClarificationService(AssistantClarificationQuestionRepository questions) {
    this.questions = questions;
  }

  /** Persists one or more exact questions, superseding only the matching pending branch. */
  @Transactional
  public List<AssistantApiModels.ClarificationQuestionResponse> create(
      UUID conversationId,
      UUID turnMessageId,
      UUID toolCallId,
      UUID warehouseId,
      List<QuestionDraft> drafts) {
    if (drafts == null || drafts.isEmpty() || drafts.size() > MAX_QUESTIONS_PER_TOOL_CALL) {
      throw new IllegalArgumentException("One to five clarification questions are required");
    }
    List<AssistantClarificationQuestion> created = new ArrayList<>();
    Set<String> branches = new LinkedHashSet<>();
    for (QuestionDraft draft : drafts) {
      draft.validate();
      if (!branches.add(draft.branchKey())) {
        throw new IllegalArgumentException("Clarification branches must be unique per tool call");
      }
      questions
          .findByConversationIdAndBranchKeyAndStatusOrderByCreatedAtAscIdAsc(
              conversationId, draft.branchKey(), AssistantClarificationStatus.PENDING)
          .forEach(AssistantClarificationQuestion::supersede);
      UUID questionId = UUID.randomUUID();
      ArrayNode options = JsonNodeFactory.instance.arrayNode();
      for (OptionDraft option : draft.options()) {
        UUID optionId = stableOptionId(questionId, option.value());
        options.addObject()
            .put("id", optionId.toString())
            .put("label", option.label())
            .put("value", option.value());
      }
      created.add(
          AssistantClarificationQuestion.create(
              questionId,
              conversationId,
              turnMessageId,
              toolCallId,
              draft.branchKey(),
              draft.kind(),
              draft.prompt(),
              warehouseId,
              draft.cabinType(),
              options));
    }
    // Hibernate may execute inserts before dirty managed updates. Flush superseded branches first
    // so the partial unique index can never observe two PENDING rows for one branch.
    questions.flush();
    questions.saveAllAndFlush(created);
    return created.stream().map(AssistantClarificationService::response).toList();
  }

  /** Resolves one owned pending question while leaving every other branch unchanged. */
  @Transactional
  public AnsweredQuestion answer(
      UUID conversationId, AssistantApiModels.ClarificationAnswerRequest answer) {
    AssistantClarificationQuestion question =
        questions
            .findByIdAndConversationId(answer.questionId(), conversationId)
            .orElseThrow(() -> new AssistantNotFoundException("Clarification question was not found"));
    question.answer(answer.optionId());
    questions.save(question);
    AssistantApiModels.ClarificationQuestionResponse response = response(question);
    AssistantApiModels.ClarificationOptionResponse selected =
        response.options().stream()
            .filter(option -> option.id().equals(answer.optionId()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Persisted clarification option is corrupt"));
    String message =
        "Ответ на уточнение «"
            + question.getPrompt()
            + "»: выбран вариант «"
            + selected.label()
            + "».";
    return new AnsweredQuestion(response, message);
  }

  /** Returns the latest durable state for every branch so reload preserves independent choices. */
  @Transactional(readOnly = true)
  public List<AssistantApiModels.ClarificationQuestionResponse> current(UUID conversationId) {
    Map<String, AssistantClarificationQuestion> latestByBranch = new LinkedHashMap<>();
    questions.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId)
        .forEach(question -> latestByBranch.put(question.getBranchKey(), question));
    return latestByBranch.values().stream()
        .map(AssistantClarificationService::response)
        .toList();
  }

  private static AssistantApiModels.ClarificationQuestionResponse response(
      AssistantClarificationQuestion question) {
    List<AssistantApiModels.ClarificationOptionResponse> options = new ArrayList<>();
    for (JsonNode option : question.getOptionsPayload()) {
      try {
        options.add(
            new AssistantApiModels.ClarificationOptionResponse(
                UUID.fromString(option.path("id").asText()),
                option.path("label").asText(),
                option.path("value").asText()));
      } catch (IllegalArgumentException invalid) {
        throw new IllegalStateException("Persisted clarification option is corrupt", invalid);
      }
    }
    return new AssistantApiModels.ClarificationQuestionResponse(
        question.getId(),
        question.getBranchKey(),
        question.getKind().name(),
        question.getPrompt(),
        question.getStatus().name(),
        options,
        question.getAnsweredOptionId(),
        question.getCreatedAt(),
        question.getAnsweredAt());
  }

  private static UUID stableOptionId(UUID questionId, String value) {
    return UUID.nameUUIDFromBytes(
        ("assistant-clarification-option:" + questionId + ':' + value)
            .getBytes(StandardCharsets.UTF_8));
  }

  /** Immutable exact option requested by a validated clarification tool. */
  public record OptionDraft(String label, String value) {
    public OptionDraft {
      label = requiredText(label, "option label", 255);
      value = requiredText(value, "option value", 255);
    }
  }

  /** One independent question branch prepared after authoritative facet validation. */
  public record QuestionDraft(
      String branchKey,
      AssistantClarificationKind kind,
      String prompt,
      String cabinType,
      List<OptionDraft> options) {
    public QuestionDraft {
      branchKey = requiredText(branchKey, "branchKey", 255);
      prompt = requiredText(prompt, "prompt", 2000);
      if (kind == null) throw new IllegalArgumentException("Clarification kind is required");
      cabinType = optionalText(cabinType, 255);
      options = options == null ? List.of() : List.copyOf(options);
    }

    private void validate() {
      if (options.size() < 2 || options.size() > MAX_OPTIONS_PER_QUESTION) {
        throw new IllegalArgumentException("A clarification requires two to thirty options");
      }
      Set<String> values = new LinkedHashSet<>();
      for (OptionDraft option : options) {
        if (!values.add(option.value())) {
          throw new IllegalArgumentException("Clarification option values must be unique");
        }
      }
    }
  }

  /** Answer transition result used both for persistence and structured SSE emission. */
  public record AnsweredQuestion(
      AssistantApiModels.ClarificationQuestionResponse question, String userMessage) {}

  private static String requiredText(String value, String field, int maximumLength) {
    String normalized = optionalText(value, maximumLength);
    if (normalized == null) throw new IllegalArgumentException(field + " is required");
    return normalized;
  }

  private static String optionalText(String value, int maximumLength) {
    if (value == null) return null;
    String normalized = value.trim();
    if (normalized.isEmpty()) return null;
    if (normalized.length() > maximumLength) {
      throw new IllegalArgumentException("value is too long");
    }
    return normalized;
  }
}
