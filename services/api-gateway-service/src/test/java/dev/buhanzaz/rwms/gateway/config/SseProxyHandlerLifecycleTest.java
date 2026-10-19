package dev.buhanzaz.rwms.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.gateway.web.GatewayUpstreamProblemWriter;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter.RequestHttpHeadersFilter;
import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter.ResponseHttpHeadersFilter;
import org.springframework.test.util.ReflectionTestUtils;

class SseProxyHandlerLifecycleTest {

  @Test
  void headerDeadlineCancelsTheOriginalExchangeAndCancelsLateResponseBodyOnce() throws Exception {
    SseProxyHandler handler = handler(Duration.ofSeconds(10));
    LateCompletingFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> upstream =
        new LateCompletingFuture<>();
    ControlledPublisher publisher = new ControlledPublisher();
    try {
      CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> deadline =
          applyHeaderTimeout(handler, upstream);

      assertThat(deadline.completeExceptionally(new TimeoutException("header deadline"))).isTrue();
      ExecutionException failure =
          org.junit.jupiter.api.Assertions.assertThrows(
              ExecutionException.class, deadline::get);
      assertThat(failure).hasCauseInstanceOf(TimeoutException.class);
      assertThat(upstream.cancellations()).isEqualTo(1);

      assertThat(upstream.complete(response(publisher))).isTrue();
      assertThat(publisher.subscriptions()).isEqualTo(1);
      assertThat(publisher.cancellations()).isEqualTo(1);
    } finally {
      handler.destroy();
    }
  }

  @Test
  void cancellingHeaderDeadlineBeforeHeadersCancelsTheOriginalExchange() {
    SseProxyHandler handler = handler(Duration.ofSeconds(10));
    CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> upstream =
        new CompletableFuture<>();
    try {
      CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> deadline =
          applyHeaderTimeout(handler, upstream);

      assertThat(deadline.cancel(true)).isTrue();
      assertThat(upstream).isCancelled();
    } finally {
      handler.destroy();
    }
  }

  @Test
  void clientDisconnectAfterHeadersBeforeSubscriptionCancelsBodyAndReleasesSlotOnce()
      throws Exception {
    CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> upstream =
        new CompletableFuture<>();
    ControlledPublisher publisher = new ControlledPublisher();
    AtomicInteger releases = new AtomicInteger();
    AtomicReference<AsyncListener> listener = new AtomicReference<>();
    AtomicReference<Runnable> scheduled = new AtomicReference<>();
    AsyncContext asyncContext = mock(AsyncContext.class);
    doAnswer(invocation -> {
          listener.set(invocation.getArgument(0, AsyncListener.class));
          return null;
        })
        .when(asyncContext)
        .addListener(any(AsyncListener.class));
    doAnswer(invocation -> {
          scheduled.set(invocation.getArgument(0, Runnable.class));
          return null;
        })
        .when(asyncContext)
        .start(any(Runnable.class));

    SseProxyHandler.StreamBridge bridge =
        bridge(asyncContext, mock(HttpServletResponse.class), upstream, releases::incrementAndGet);
    bridge.start();
    upstream.complete(response(publisher));

    assertThat(listener.get()).isNotNull();
    assertThat(scheduled.get()).isNotNull();
    listener.get().onError(new AsyncEvent(asyncContext));
    scheduled.get().run();

    assertThat(publisher.subscriptions()).isEqualTo(1);
    assertThat(publisher.cancellations()).isEqualTo(1);
    assertThat(releases).hasValue(1);
    verify(asyncContext, times(1)).complete();
  }

  @Test
  void upstreamCompletionReleasesSlotOnceWithoutCancellingCompletedSubscription() throws Exception {
    CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> upstream =
        new CompletableFuture<>();
    ControlledPublisher publisher = new ControlledPublisher();
    AtomicInteger releases = new AtomicInteger();
    AsyncContext asyncContext = immediateAsyncContext();
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(response.getOutputStream()).thenReturn(new ReadyServletOutputStream());

    SseProxyHandler.StreamBridge bridge = bridge(asyncContext, response, upstream, releases::incrementAndGet);
    bridge.start();
    upstream.complete(response(publisher));
    publisher.complete();
    publisher.complete();

    assertThat(releases).hasValue(1);
    assertThat(publisher.cancellations()).isZero();
    verify(asyncContext, times(1)).complete();
  }

  @Test
  void upstreamErrorReleasesSlotOnce() throws Exception {
    CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> upstream =
        new CompletableFuture<>();
    ControlledPublisher publisher = new ControlledPublisher();
    AtomicInteger releases = new AtomicInteger();
    AsyncContext asyncContext = immediateAsyncContext();
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(response.getOutputStream()).thenReturn(new ReadyServletOutputStream());

    SseProxyHandler.StreamBridge bridge = bridge(asyncContext, response, upstream, releases::incrementAndGet);
    bridge.start();
    upstream.complete(response(publisher));
    publisher.fail(new IOException("upstream closed abruptly"));
    publisher.fail(new IOException("duplicate terminal callback"));

    assertThat(releases).hasValue(1);
    verify(asyncContext, times(1)).complete();
  }

  @Test
  void responseWriteFailureCancelsAnUnsubscribedBodyAndReleasesSlot() throws Exception {
    CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> upstream =
        new CompletableFuture<>();
    ControlledPublisher publisher = new ControlledPublisher();
    AtomicInteger releases = new AtomicInteger();
    AsyncContext asyncContext = immediateAsyncContext();
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(response.getOutputStream()).thenThrow(new IOException("client disconnected"));

    SseProxyHandler.StreamBridge bridge = bridge(asyncContext, response, upstream, releases::incrementAndGet);
    bridge.start();
    upstream.complete(response(publisher));

    assertThat(publisher.subscriptions()).isEqualTo(1);
    assertThat(publisher.cancellations()).isEqualTo(1);
    assertThat(releases).hasValue(1);
    verify(asyncContext, times(1)).complete();
  }

  @Test
  void rejectedAsyncScheduleAfterHeadersCancelsAnUnsubscribedBodyAndReleasesSlot() {
    CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> upstream =
        new CompletableFuture<>();
    ControlledPublisher publisher = new ControlledPublisher();
    AtomicInteger releases = new AtomicInteger();
    AsyncContext asyncContext = mock(AsyncContext.class);
    doAnswer(invocation -> {
          throw new IllegalStateException("client already disconnected");
        })
        .when(asyncContext)
        .start(any(Runnable.class));

    SseProxyHandler.StreamBridge bridge =
        bridge(asyncContext, mock(HttpServletResponse.class), upstream, releases::incrementAndGet);
    bridge.start();
    upstream.complete(response(publisher));

    assertThat(publisher.subscriptions()).isEqualTo(1);
    assertThat(publisher.cancellations()).isEqualTo(1);
    assertThat(releases).hasValue(1);
    verify(asyncContext, times(1)).complete();
  }

  @Test
  void flushesIdleUpstreamHeartbeatBeforeTheServletResponseBufferFills() throws Exception {
    CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> upstream =
        new CompletableFuture<>();
    ControlledPublisher publisher = new ControlledPublisher();
    RecordingServletOutputStream output = new RecordingServletOutputStream();
    AsyncContext asyncContext = immediateAsyncContext();
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(response.getOutputStream()).thenReturn(output);

    SseProxyHandler.StreamBridge bridge = bridge(asyncContext, response, upstream, () -> {});
    bridge.start();
    upstream.complete(response(publisher));
    publisher.next(": keep-alive\n\n");

    assertThat(output.text()).isEqualTo(": keep-alive\n\n");
    assertThat(output.flushes()).isEqualTo(1);
  }

  @Test
  void clientDisconnectStopsFurtherHeartbeatWritesAndReleasesTheStream() throws Exception {
    CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> upstream =
        new CompletableFuture<>();
    ControlledPublisher publisher = new ControlledPublisher();
    RecordingServletOutputStream output = new RecordingServletOutputStream();
    AtomicInteger releases = new AtomicInteger();
    AtomicReference<AsyncListener> listener = new AtomicReference<>();
    AsyncContext asyncContext = immediateAsyncContext(listener);
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(response.getOutputStream()).thenReturn(output);

    SseProxyHandler.StreamBridge bridge = bridge(asyncContext, response, upstream, releases::incrementAndGet);
    bridge.start();
    upstream.complete(response(publisher));
    publisher.next(": keep-alive\n\n");
    listener.get().onError(new AsyncEvent(asyncContext));
    publisher.next(": keep-alive\n\n");

    assertThat(output.text()).isEqualTo(": keep-alive\n\n");
    assertThat(output.flushes()).isEqualTo(1);
    assertThat(publisher.cancellations()).isEqualTo(1);
    assertThat(releases).hasValue(1);
    verify(asyncContext, times(1)).complete();
  }

  @Test
  void relaysEventAndHeartbeatBytesInOrderWithoutInterleavingCorruption() throws Exception {
    CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> upstream =
        new CompletableFuture<>();
    ControlledPublisher publisher = new ControlledPublisher();
    RecordingServletOutputStream output = new RecordingServletOutputStream();
    AsyncContext asyncContext = immediateAsyncContext();
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(response.getOutputStream()).thenReturn(output);

    SseProxyHandler.StreamBridge bridge = bridge(asyncContext, response, upstream, () -> {});
    bridge.start();
    upstream.complete(response(publisher));
    publisher.next("id: 1\nevent: warehouse-invalidation\ndata: {\"scope\":\"RESYNC\"}\n\n");
    publisher.next(": keep-alive\n\n");

    assertThat(output.text())
        .isEqualTo(
            "id: 1\nevent: warehouse-invalidation\ndata: {\"scope\":\"RESYNC\"}\n\n"
                + ": keep-alive\n\n");
    assertThat(output.flushes()).isEqualTo(2);
  }

  @Test
  void defersHeartbeatFlushUntilTomcatSignalsWriteCapacityWithoutAbortingTheStream()
      throws Exception {
    CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> upstream =
        new CompletableFuture<>();
    ControlledPublisher publisher = new ControlledPublisher();
    BackpressuredServletOutputStream output = new BackpressuredServletOutputStream();
    AtomicInteger releases = new AtomicInteger();
    AsyncContext asyncContext = immediateAsyncContext();
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(response.getOutputStream()).thenReturn(output);

    SseProxyHandler.StreamBridge bridge = bridge(asyncContext, response, upstream, releases::incrementAndGet);
    bridge.start();
    upstream.complete(response(publisher));
    publisher.next(": keep-alive\n\n");
    output.signalWriteCapacity();
    output.signalWriteCapacity();

    assertThat(output.text()).isEqualTo(": keep-alive\n\n");
    assertThat(output.flushes()).isEqualTo(1);
    assertThat(publisher.cancellations()).isZero();
    assertThat(releases).hasValue(0);
  }

  private static SseProxyHandler.StreamBridge bridge(
      AsyncContext asyncContext,
      HttpServletResponse response,
      CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> upstream,
      Runnable release) {
    return new SseProxyHandler.StreamBridge(
        asyncContext,
        mock(HttpServletRequest.class),
        response,
        upstream,
        (headers, statusCode) -> headers,
        null,
        release);
  }

  @SuppressWarnings("unchecked")
  private static CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>>
      applyHeaderTimeout(
          SseProxyHandler handler,
          CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> upstream) {
    return (CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>>)
        ReflectionTestUtils.invokeMethod(handler, "applyHeaderTimeout", upstream);
  }

  private static SseProxyHandler handler(Duration headerTimeout) {
    GatewayProperties properties = new GatewayProperties();
    properties.getSse().setHeaderTimeout(headerTimeout);
    HttpClientSettings httpClientSettings = mock(HttpClientSettings.class);
    when(httpClientSettings.connectTimeout()).thenReturn(Duration.ofSeconds(2));
    ObjectProvider<RequestHttpHeadersFilter> requestFilters = mock(ObjectProvider.class);
    ObjectProvider<ResponseHttpHeadersFilter> responseFilters = mock(ObjectProvider.class);
    return new SseProxyHandler(
        properties,
        httpClientSettings,
        mock(GatewayUpstreamProblemWriter.class),
        requestFilters,
        responseFilters);
  }

  private static AsyncContext immediateAsyncContext() {
    AsyncContext asyncContext = mock(AsyncContext.class);
    doAnswer(invocation -> {
          invocation.getArgument(0, Runnable.class).run();
          return null;
        })
        .when(asyncContext)
        .start(any(Runnable.class));
    return asyncContext;
  }

  private static AsyncContext immediateAsyncContext(AtomicReference<AsyncListener> listener) {
    AsyncContext asyncContext = immediateAsyncContext();
    doAnswer(invocation -> {
          listener.set(invocation.getArgument(0, AsyncListener.class));
          return null;
        })
        .when(asyncContext)
        .addListener(any(AsyncListener.class));
    return asyncContext;
  }

  private static HttpResponse<Flow.Publisher<List<ByteBuffer>>> response(
      Flow.Publisher<List<ByteBuffer>> body) {
    return new HttpResponse<>() {
      @Override
      public int statusCode() {
        return 200;
      }

      @Override
      public HttpRequest request() {
        return HttpRequest.newBuilder(uri()).build();
      }

      @Override
      public Optional<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> previousResponse() {
        return Optional.empty();
      }

      @Override
      public java.net.http.HttpHeaders headers() {
        return java.net.http.HttpHeaders.of(Map.of(), (name, value) -> true);
      }

      @Override
      public Flow.Publisher<List<ByteBuffer>> body() {
        return body;
      }

      @Override
      public Optional<SSLSession> sslSession() {
        return Optional.empty();
      }

      @Override
      public URI uri() {
        return URI.create("http://upstream.test/events");
      }

      @Override
      public HttpClient.Version version() {
        return HttpClient.Version.HTTP_1_1;
      }
    };
  }

  private static final class ReadyServletOutputStream extends ServletOutputStream {
    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setWriteListener(WriteListener listener) {
      // The bridge is driven by the publisher completion in this test.
    }

    @Override
    public void write(int ignored) {
      // The completion and error tests do not write a data item.
    }
  }

  private static final class RecordingServletOutputStream extends ServletOutputStream {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final AtomicInteger flushes = new AtomicInteger();

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setWriteListener(WriteListener listener) {
      // The bridge is driven by the publisher in these tests.
    }

    @Override
    public void write(int value) {
      this.bytes.write(value);
    }

    @Override
    public void write(byte[] value, int offset, int length) {
      this.bytes.write(value, offset, length);
    }

    @Override
    public void flush() {
      this.flushes.incrementAndGet();
    }

    String text() {
      return new String(this.bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    int flushes() {
      return this.flushes.get();
    }
  }

  private static final class BackpressuredServletOutputStream extends ServletOutputStream {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final AtomicInteger flushes = new AtomicInteger();
    private WriteListener listener;
    private boolean ready = true;

    @Override
    public boolean isReady() {
      return this.ready;
    }

    @Override
    public void setWriteListener(WriteListener listener) {
      this.listener = listener;
    }

    @Override
    public void write(int value) {
      this.bytes.write(value);
      this.ready = false;
    }

    @Override
    public void write(byte[] value, int offset, int length) {
      this.bytes.write(value, offset, length);
      this.ready = false;
    }

    @Override
    public void flush() {
      this.flushes.incrementAndGet();
    }

    void signalWriteCapacity() throws IOException {
      this.ready = true;
      if (this.listener != null) {
        this.listener.onWritePossible();
      }
    }

    String text() {
      return new String(this.bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    int flushes() {
      return this.flushes.get();
    }
  }

  private static final class ControlledPublisher implements Flow.Publisher<List<ByteBuffer>> {
    private final AtomicReference<Flow.Subscriber<? super List<ByteBuffer>>> subscriber =
        new AtomicReference<>();
    private final AtomicInteger subscriptions = new AtomicInteger();
    private final AtomicInteger cancellations = new AtomicInteger();

    @Override
    public void subscribe(Flow.Subscriber<? super List<ByteBuffer>> subscriber) {
      this.subscriptions.incrementAndGet();
      this.subscriber.set(subscriber);
      subscriber.onSubscribe(
          new Flow.Subscription() {
            @Override
            public void request(long ignored) {
              // Completion/error is triggered explicitly by the test.
            }

            @Override
            public void cancel() {
              cancellations.incrementAndGet();
            }
          });
    }

    int subscriptions() {
      return this.subscriptions.get();
    }

    int cancellations() {
      return this.cancellations.get();
    }

    void complete() {
      this.subscriber.get().onComplete();
    }

    void fail(Throwable error) {
      this.subscriber.get().onError(error);
    }

    void next(String value) {
      this.subscriber
          .get()
          .onNext(List.of(ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8))));
    }
  }

  private static final class LateCompletingFuture<T> extends CompletableFuture<T> {
    private final AtomicInteger cancellations = new AtomicInteger();

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      this.cancellations.incrementAndGet();
      return false;
    }

    int cancellations() {
      return this.cancellations.get();
    }
  }
}
