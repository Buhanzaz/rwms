package dev.buhanzaz.rwms.assistant.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.buhanzaz.rwms.assistant.config.AssistantLlmProperties;
import dev.buhanzaz.rwms.assistant.service.AssistantProviderException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.ObjectMapper;

class HttpChatCompletionClientTest {
  private HttpServer server;
  private ExecutorService serverExecutor;

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
    if (serverExecutor != null) serverExecutor.shutdownNow();
  }

  @Test
  void streamsProviderTextAndCompletion() throws Exception {
    start(
        exchange -> {
          assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
              .isEqualTo("Bearer provider-test-key");
          writeSse(
              exchange,
              """
              data: {"choices":[{"delta":{"content":"Hello"}}]}

              data: {"choices":[{"delta":{"content":" world"},"finish_reason":"stop"}]}

              data: [DONE]

              """);
        });
    RecordingListener listener = new RecordingListener();

    client().stream(request(), listener);

    assertThat(listener.content).containsExactly("Hello", " world");
    assertThat(listener.finish.get()).isEqualTo("stop");
  }

  @Test
  void acceptsMistralStreamClosedAfterTheFinalFinishChunk() throws Exception {
    start(
        exchange ->
            writeSse(
                exchange,
                """
                data: {"choices":[{"delta":{"content":"Готово"},"finish_reason":"stop"}]}

                """));
    RecordingListener listener = new RecordingListener();

    client().stream(request(), listener);

    assertThat(listener.content).containsExactly("Готово");
    assertThat(listener.finish.get()).isEqualTo("stop");
  }

  @Test
  void streamsModelSelectedToolCallWithoutParsingItsArguments() throws Exception {
    start(
        exchange ->
            writeSse(
                exchange,
                """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"search_available_cabins","arguments":"{\\"warehouseId\\":\\"00000000-0000-0000-0000-000000000011\\",\\"groups\\":[]}"}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """));
    RecordingListener listener = new RecordingListener();

    client().stream(request(), listener);

    assertThat(listener.tools)
        .containsExactly(
            new ChatCompletionClient.ToolCallDelta(
                0,
                "call_1",
                "search_available_cabins",
                "{\"warehouseId\":\"00000000-0000-0000-0000-000000000011\",\"groups\":[]}"));
    assertThat(listener.finish.get()).isEqualTo("tool_calls");
  }

  @Test
  void sendsRequiredChoiceWhenTheTurnMustUseTheOnlySuppliedTool() throws Exception {
    AtomicReference<String> requestBody = new AtomicReference<>();
    start(
        exchange -> {
          requestBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          writeSse(
              exchange,
              """
              data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"search_available_cabins","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}

              data: [DONE]

              """);
        });
    ChatCompletionClient.ChatCompletionRequest requiredRequest =
        new ChatCompletionClient.ChatCompletionRequest(
            "provider-test-model",
            List.of(ChatCompletionClient.ChatMessage.user("find alternatives")),
            List.of(
                new ChatCompletionClient.ToolDefinition(
                    "search_available_cabins", "search", new ObjectMapper().createObjectNode())),
            true);

    client().stream(requiredRequest, new RecordingListener());

    tools.jackson.databind.JsonNode json = new ObjectMapper().readTree(requestBody.get());
    assertThat(json.path("tool_choice").asText()).isEqualTo("required");
    assertThat(json.path("tools")).hasSize(1);
    assertThat(json.path("tools").get(0).path("function").path("name").asText())
        .isEqualTo("search_available_cabins");
  }

  @Test
  void turnsProviderFailureIntoSafeException() throws Exception {
    start(
        exchange -> {
          byte[] body = "{\"error\":\"provider diagnostic must not escape\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(503, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    Throwable failure = catchThrowable(() -> client().stream(request(), new RecordingListener()));
    assertThat(failure)
        .isInstanceOf(AssistantProviderException.class)
        .hasMessage("LLM provider request failed");
    assertThat(failure.getMessage()).doesNotContain("diagnostic");
  }

  @Test
  void retriesOneTransientFailureBeforeAnyStreamEvent() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    start(
        exchange -> {
          if (attempts.incrementAndGet() == 1) {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
            return;
          }
          writeSse(
              exchange,
              """
              data: {"choices":[{"delta":{"content":"После повтора"},"finish_reason":"stop"}]}

              data: [DONE]

              """);
        });
    RecordingListener listener = new RecordingListener();

    client().stream(request(), listener);

    assertThat(attempts).hasValue(2);
    assertThat(listener.content).containsExactly("После повтора");
    assertThat(listener.finish.get()).isEqualTo("stop");
  }

  @Test
  void doesNotRetryAfterStreamingHasStarted() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    start(
        exchange -> {
          attempts.incrementAndGet();
          writeSse(
              exchange,
              """
              data: {"choices":[{"delta":{"content":"Частичный ответ"}}]}

              """);
        });
    RecordingListener listener = new RecordingListener();

    Throwable failure = catchThrowable(() -> client().stream(request(), listener));

    assertThat(failure).isInstanceOf(AssistantProviderException.class);
    assertThat(attempts).hasValue(1);
    assertThat(listener.content).containsExactly("Частичный ответ");
  }

  @Test
  void closesEachProviderStreamWhenHeadersArriveWithoutAnyBodyData() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    AtomicInteger closedStreams = new AtomicInteger();
    start(
        exchange -> {
          attempts.incrementAndGet();
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          writeAfterTimeoutUntilClosed(exchange, closedStreams);
        });

    Throwable failure =
        catchThrowable(
            () ->
                client(Duration.ofSeconds(2), Duration.ofMillis(100))
                    .stream(request(), new RecordingListener()));

    assertThat(failure)
        .isInstanceOf(AssistantProviderException.class)
        .hasMessage("LLM provider stream timed out");
    assertThat(attempts).hasValue(2);
    awaitAtMost(Duration.ofSeconds(2), () -> closedStreams.get() == 2);
  }

  @Test
  void closesAStreamThatStallsAfterAChunkWithoutRetryingIt() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    AtomicInteger closedStreams = new AtomicInteger();
    RecordingListener listener = new RecordingListener();
    start(
        exchange -> {
          attempts.incrementAndGet();
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          OutputStream body = exchange.getResponseBody();
          body.write(
              "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
                  .getBytes(StandardCharsets.UTF_8));
          body.flush();
          writeAfterTimeoutUntilClosed(exchange, closedStreams);
        });

    Throwable failure =
        catchThrowable(
            () ->
                client(Duration.ofSeconds(2), Duration.ofMillis(100))
                    .stream(request(), listener));

    assertThat(failure)
        .isInstanceOf(AssistantProviderException.class)
        .hasMessage("LLM provider stream timed out");
    assertThat(attempts).hasValue(1);
    assertThat(listener.content).containsExactly("partial");
    awaitAtMost(Duration.ofSeconds(2), () -> closedStreams.get() == 1);
  }

  @Test
  @Timeout(5)
  void overallDeadlineClosesAnActiveStreamWhoseChunksStayWithinTheIdleTimeout() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    AtomicInteger closedStreams = new AtomicInteger();
    RecordingListener listener = new RecordingListener();
    start(
        exchange -> {
          attempts.incrementAndGet();
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream body = exchange.getResponseBody()) {
            while (true) {
              body.write(
                  "data: {\"choices\":[{\"delta\":{\"content\":\"tick\"}}]}\n\n"
                      .getBytes(StandardCharsets.UTF_8));
              body.flush();
              Thread.sleep(30);
            }
          } catch (IOException expected) {
            closedStreams.incrementAndGet();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });

    Throwable failure =
        catchThrowable(
            () ->
                client(Duration.ofMillis(500), Duration.ofSeconds(2))
                    .stream(request(), listener));

    assertThat(failure)
        .isInstanceOf(AssistantProviderException.class)
        .hasMessage("LLM provider stream timed out");
    assertThat(attempts).hasValue(1);
    assertThat(listener.content).isNotEmpty();
    awaitAtMost(Duration.ofSeconds(2), () -> closedStreams.get() == 1);
  }

  private HttpChatCompletionClient client() {
    return client(Duration.ofSeconds(5), Duration.ofSeconds(1));
  }

  private HttpChatCompletionClient client(Duration requestTimeout, Duration streamIdleTimeout) {
    AssistantLlmProperties properties =
        new AssistantLlmProperties(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
            "provider-test-key",
            "provider-test-model",
            Duration.ofSeconds(2),
            requestTimeout,
            streamIdleTimeout,
            false);
    return new HttpChatCompletionClient(properties, new ObjectMapper(), HttpClient.newHttpClient());
  }

  private static ChatCompletionClient.ChatCompletionRequest request() {
    return new ChatCompletionClient.ChatCompletionRequest(
        "provider-test-model",
        List.of(ChatCompletionClient.ChatMessage.user("hello")),
        List.of());
  }

  private void start(ExchangeHandler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    serverExecutor = Executors.newCachedThreadPool();
    server.setExecutor(serverExecutor);
    server.createContext("/v1/chat/completions", exchange -> handler.handle(exchange));
    server.start();
  }

  private static void writeAfterTimeoutUntilClosed(
      HttpExchange exchange, AtomicInteger closedStreams) {
    try (OutputStream body = exchange.getResponseBody()) {
      Thread.sleep(250);
      byte[] lateData = new byte[16 * 1024];
      Instant deadline = Instant.now().plusSeconds(2);
      while (Instant.now().isBefore(deadline)) {
        body.write(lateData);
        body.flush();
      }
      throw new AssertionError("Provider stream remained open after the client timeout");
    } catch (IOException expected) {
      closedStreams.incrementAndGet();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } finally {
      exchange.close();
    }
  }

  private static void awaitAtMost(Duration timeout, CheckedCondition condition) throws Exception {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      if (condition.matches()) return;
      Thread.sleep(20);
    }
    assertThat(condition.matches()).isTrue();
  }

  private static void writeSse(HttpExchange exchange, String body) throws IOException {
    byte[] data = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
    exchange.sendResponseHeaders(200, data.length);
    exchange.getResponseBody().write(data);
    exchange.close();
  }

  @FunctionalInterface
  private interface ExchangeHandler {
    void handle(HttpExchange exchange) throws IOException;
  }

  @FunctionalInterface
  private interface CheckedCondition {
    boolean matches() throws Exception;
  }

  private static final class RecordingListener implements ChatCompletionClient.ChatCompletionListener {
    private final List<String> content = new ArrayList<>();
    private final List<ChatCompletionClient.ToolCallDelta> tools = new ArrayList<>();
    private final AtomicReference<String> finish = new AtomicReference<>();

    @Override
    public void onContent(String value) {
      content.add(value);
    }

    @Override
    public void onToolCallDelta(ChatCompletionClient.ToolCallDelta value) {
      tools.add(value);
    }

    @Override
    public void onFinish(String value) {
      finish.set(value);
    }
  }
}
