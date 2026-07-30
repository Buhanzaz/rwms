package dev.buhanzaz.rwms.assistant.integration;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** Minimal OpenAI-compatible /chat/completions transport abstraction. */
public interface ChatCompletionClient {
  void stream(ChatCompletionRequest request, ChatCompletionListener listener);

  record ChatCompletionRequest(
      String model,
      List<ChatMessage> messages,
      List<ToolDefinition> tools,
      boolean toolRequired) {
    public ChatCompletionRequest(
        String model, List<ChatMessage> messages, List<ToolDefinition> tools) {
      this(model, messages, tools, false);
    }

    public ChatCompletionRequest {
      messages = List.copyOf(messages);
      tools = List.copyOf(tools);
    }
  }

  record ChatMessage(
      String role, String content, List<ProviderToolCall> toolCalls, String toolCallId) {
    public ChatMessage {
      if (role == null || role.isBlank()) throw new IllegalArgumentException("Chat role is required");
      toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static ChatMessage system(String content) {
      return new ChatMessage("system", content, List.of(), null);
    }

    public static ChatMessage user(String content) {
      return new ChatMessage("user", content, List.of(), null);
    }

    public static ChatMessage assistant(String content) {
      return new ChatMessage("assistant", content, List.of(), null);
    }

    public static ChatMessage assistantToolCalls(List<ProviderToolCall> toolCalls) {
      return new ChatMessage("assistant", null, toolCalls, null);
    }

    public static ChatMessage tool(String toolCallId, String content) {
      return new ChatMessage("tool", content, List.of(), toolCallId);
    }
  }

  record ToolDefinition(String name, String description, JsonNode parameters) {}

  record ProviderToolCall(String id, String name, String arguments) {
    public ProviderToolCall {
      if (id == null || id.isBlank() || name == null || name.isBlank()) {
        throw new IllegalArgumentException("Provider tool call is incomplete");
      }
      arguments = arguments == null || arguments.isBlank() ? "{}" : arguments;
    }
  }

  record ToolCallDelta(int index, String id, String name, String argumentsFragment) {}

  interface ChatCompletionListener {
    void onContent(String text);

    void onToolCallDelta(ToolCallDelta toolCall);

    void onFinish(String finishReason);
  }
}
