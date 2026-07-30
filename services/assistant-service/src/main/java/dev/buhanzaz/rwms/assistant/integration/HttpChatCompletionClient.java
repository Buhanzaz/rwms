package dev.buhanzaz.rwms.assistant.integration;

import dev.buhanzaz.rwms.assistant.config.AssistantLlmProperties;
import dev.buhanzaz.rwms.assistant.service.AssistantProviderException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Direct OpenAI-compatible HTTP implementation. No provider SDK or Spring AI
 * dependency is used, keeping a provider change an explicit config/restart.
 */
@Component
public class HttpChatCompletionClient implements ChatCompletionClient {
  private final AssistantLlmProperties properties;
  private final ObjectMapper mapper;
  private final HttpClient client;

  @Autowired
  public HttpChatCompletionClient(AssistantLlmProperties properties, ObjectMapper mapper) {
    this(
        properties,
        mapper,
        HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
  }

  HttpChatCompletionClient(
      AssistantLlmProperties properties, ObjectMapper mapper, HttpClient client) {
    this.properties = properties;
    this.mapper = mapper;
    this.client = client;
  }

  @Override
  public void stream(ChatCompletionRequest request, ChatCompletionListener listener) {
    if (!properties.apiKeyConfigured()) {
      throw new AssistantProviderException("LLM_API_KEY is required before a turn can run");
    }
    TrackingListener tracking = new TrackingListener(listener);
    try {
      streamOnce(request, tracking);
    } catch (RetryableProviderException firstFailure) {
      if (tracking.receivedAnything()) {
        throw firstFailure;
      }
      streamOnce(request, tracking);
    }
  }

  private void streamOnce(ChatCompletionRequest request, ChatCompletionListener listener) {
    try {
      HttpRequest httpRequest =
          HttpRequest.newBuilder(endpoint())
              .timeout(properties.requestTimeout())
              .header("Accept", "text/event-stream")
              .header("Content-Type", "application/json")
              .header("Authorization", "Bearer " + properties.apiKey())
              .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestBody(request))))
              .build();
      HttpResponse<java.io.InputStream> response =
          client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        closeQuietly(response.body());
        if (isRetryableStatus(response.statusCode())) {
          throw new RetryableProviderException("LLM provider request failed");
        }
        throw new AssistantProviderException("LLM provider request failed");
      }
      consumeSse(response.body(), listener);
    } catch (IOException exception) {
      throw new RetryableProviderException("LLM provider request failed", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssistantProviderException("LLM provider request interrupted", exception);
    }
  }

  private void consumeSse(
      java.io.InputStream stream, ChatCompletionListener listener) throws IOException {
    boolean done = false;
    boolean finished = false;
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (!line.startsWith("data:")) continue;
        String payload = line.substring("data:".length()).trim();
        if ("[DONE]".equals(payload)) {
          done = true;
          break;
        }
        finished |= consumeChunk(mapper.readTree(payload), listener);
      }
    }
    if (!done && !finished) {
      throw new RetryableProviderException("LLM provider ended an incomplete stream");
    }
  }

  private static boolean isRetryableStatus(int status) {
    return status == 408 || status == 429 || status >= 500;
  }

  private static void closeQuietly(java.io.InputStream stream) {
    try {
      stream.close();
    } catch (IOException ignored) {
      // The status is already the useful error; never expose provider body.
    }
  }

  private boolean consumeChunk(JsonNode chunk, ChatCompletionListener listener) {
    boolean finished = false;
    JsonNode choices = chunk.path("choices");
    if (!choices.isArray()) return false;
    for (JsonNode choice : choices) {
      JsonNode delta = choice.path("delta");
      JsonNode content = delta.path("content");
      if (content.isTextual() && !content.asText().isEmpty()) {
        listener.onContent(content.asText());
      }
      JsonNode toolCalls = delta.path("tool_calls");
      if (toolCalls.isArray()) {
        for (JsonNode toolCall : toolCalls) {
          int index = toolCall.path("index").isInt() ? toolCall.path("index").intValue() : 0;
          JsonNode function = toolCall.path("function");
          listener.onToolCallDelta(
              new ToolCallDelta(
                  index,
                  text(toolCall.path("id")),
                  text(function.path("name")),
                  text(function.path("arguments"))));
        }
      }
      String finishReason = text(choice.path("finish_reason"));
      if (finishReason != null) {
        listener.onFinish(finishReason);
        finished = true;
      }
    }
    return finished;
  }

  private ObjectNode requestBody(ChatCompletionRequest request) {
    ObjectNode root = JsonNodeFactory.instance.objectNode();
    root.put("model", request.model());
    root.put("stream", true);
    ArrayNode messages = root.putArray("messages");
    for (ChatMessage message : request.messages()) {
      ObjectNode item = messages.addObject();
      item.put("role", message.role());
      if (message.content() != null) item.put("content", message.content());
      if (message.toolCallId() != null) item.put("tool_call_id", message.toolCallId());
      if (!message.toolCalls().isEmpty()) {
        ArrayNode toolCalls = item.putArray("tool_calls");
        for (ProviderToolCall call : message.toolCalls()) {
          ObjectNode tool = toolCalls.addObject();
          tool.put("id", call.id());
          tool.put("type", "function");
          tool.putObject("function").put("name", call.name()).put("arguments", call.arguments());
        }
      }
    }
    ArrayNode tools = root.putArray("tools");
    for (ToolDefinition definition : request.tools()) {
      ObjectNode tool = tools.addObject();
      tool.put("type", "function");
      ObjectNode function = tool.putObject("function");
      function.put("name", definition.name());
      function.put("description", definition.description());
      function.set("parameters", definition.parameters().deepCopy());
    }
    root.put("tool_choice", request.toolRequired() ? "required" : "auto");
    return root;
  }

  private URI endpoint() {
    String base = properties.baseUrl();
    if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
    return URI.create(base + "/chat/completions");
  }

  private static String text(JsonNode value) {
    return value != null && value.isTextual() ? value.asText() : null;
  }

  private static final class TrackingListener implements ChatCompletionListener {
    private final ChatCompletionListener delegate;
    private boolean receivedAnything;

    private TrackingListener(ChatCompletionListener delegate) {
      this.delegate = delegate;
    }

    @Override
    public void onContent(String text) {
      receivedAnything = true;
      delegate.onContent(text);
    }

    @Override
    public void onToolCallDelta(ToolCallDelta toolCall) {
      receivedAnything = true;
      delegate.onToolCallDelta(toolCall);
    }

    @Override
    public void onFinish(String finishReason) {
      receivedAnything = true;
      delegate.onFinish(finishReason);
    }

    private boolean receivedAnything() {
      return receivedAnything;
    }
  }

  private static final class RetryableProviderException extends AssistantProviderException {
    private RetryableProviderException(String message) {
      super(message);
    }

    private RetryableProviderException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
