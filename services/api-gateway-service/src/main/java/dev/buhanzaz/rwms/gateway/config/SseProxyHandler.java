package dev.buhanzaz.rwms.gateway.config;

import dev.buhanzaz.rwms.gateway.web.GatewayUpstreamProblemWriter;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.cloud.gateway.server.mvc.common.MvcUtils;
import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter.RequestHttpHeadersFilter;
import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter.ResponseHttpHeadersFilter;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Proxies the warehouse invalidation streams with Servlet async I/O instead of the blocking MVC
 * proxy exchange. A client disconnect therefore cancels the upstream subscription rather than
 * asking {@code JdkClientHttpResponse.close()} to drain an unbounded SSE body.
 */
@Component
final class SseProxyHandler
    implements HandlerFunction<ServerResponse>, ApplicationListener<ContextRefreshedEvent>, DisposableBean {

  private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);
  private static final int HTTP_CLIENT_THREADS = 4;
  private static final int HTTP_CLIENT_QUEUE_CAPACITY = 256;
  private static final int WRITE_CHUNK_SIZE = 8 * 1024;
  private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

  private final ObjectProvider<RequestHttpHeadersFilter> requestHeaderFiltersProvider;
  private final ObjectProvider<ResponseHttpHeadersFilter> responseHeaderFiltersProvider;
  private final GatewayUpstreamProblemWriter upstreamProblems;
  private final ExecutorService httpClientExecutor;
  private final HttpClient httpClient;
  private final Semaphore connections;
  private final Duration headerTimeout;

  private volatile List<RequestHttpHeadersFilter> requestHeaderFilters = List.of();
  private volatile List<ResponseHttpHeadersFilter> responseHeaderFilters = List.of();

  SseProxyHandler(
      GatewayProperties properties,
      HttpClientSettings httpClientSettings,
      GatewayUpstreamProblemWriter upstreamProblems,
      ObjectProvider<RequestHttpHeadersFilter> requestHeaderFilters,
      ObjectProvider<ResponseHttpHeadersFilter> responseHeaderFilters) {
    this.requestHeaderFiltersProvider = requestHeaderFilters;
    this.responseHeaderFiltersProvider = responseHeaderFilters;
    this.upstreamProblems = upstreamProblems;
    this.connections = new Semaphore(properties.getSse().getMaxConnections());
    this.httpClientExecutor =
        new ThreadPoolExecutor(
            HTTP_CLIENT_THREADS,
            HTTP_CLIENT_THREADS,
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(HTTP_CLIENT_QUEUE_CAPACITY),
            daemonThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy());
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(
                nonNullDuration(httpClientSettings.connectTimeout(), DEFAULT_CONNECT_TIMEOUT))
            .executor(this.httpClientExecutor)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    this.headerTimeout = properties.getSse().getHeaderTimeout();
  }

  @Override
  public ServerResponse handle(ServerRequest request) {
    if (!this.connections.tryAcquire()) {
      return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
          .header(HttpHeaders.RETRY_AFTER, "1")
          .build();
    }

    AtomicBoolean released = new AtomicBoolean();
    Runnable release = () -> release(released);
    try {
      HttpRequest upstreamRequest = upstreamRequest(request);
      CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> upstream =
          this.httpClient.sendAsync(upstreamRequest, HttpResponse.BodyHandlers.ofPublisher());
      CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> timedUpstream =
          applyHeaderTimeout(upstream);
      return new AsyncSseProxyResponse(
          timedUpstream,
          this::filterResponseHeaders,
          this.upstreamProblems,
          release);
    } catch (RuntimeException exception) {
      release.run();
      throw exception;
    }
  }

  @Override
  public void onApplicationEvent(ContextRefreshedEvent event) {
    initializeHeaderFilters();
  }

  @Override
  public void destroy() {
    this.httpClientExecutor.shutdownNow();
  }

  private HttpRequest upstreamRequest(ServerRequest request) {
    URI target = MvcUtils.getAttribute(request, MvcUtils.GATEWAY_REQUEST_URL_ATTR);
    if (target == null) {
      throw new IllegalStateException("No routeUri resolved");
    }
    URI upstreamUri =
        UriComponentsBuilder.fromUri(request.uri())
            .scheme(target.getScheme())
            .host(target.getHost())
            .port(target.getPort())
            .replaceQueryParams(MvcUtils.encodeQueryParams(request.params()))
            .build(true)
            .toUri();

    HttpHeaders filteredHeaders = filterRequestHeaders(request);
    HttpRequest.Builder builder = HttpRequest.newBuilder(upstreamUri).GET();
    filteredHeaders.forEach(
        (name, values) -> {
          if (!restrictedRequestHeader(name)) {
            values.forEach(value -> builder.header(name, value));
          }
        });
    return builder.build();
  }

  private HttpHeaders filterRequestHeaders(ServerRequest request) {
    initializeHeaderFilters();
    HttpHeaders headers = new HttpHeaders();
    headers.putAll(request.headers().asHttpHeaders());
    for (RequestHttpHeadersFilter filter : this.requestHeaderFilters) {
      headers = filter.apply(headers, request);
    }
    boolean preserveHost =
        (boolean)
            request
                .attributes()
                .getOrDefault(MvcUtils.PRESERVE_HOST_HEADER_ATTRIBUTE, false);
    if (!preserveHost) {
      headers.remove(HttpHeaders.HOST);
    }
    return headers;
  }

  private HttpHeaders filterResponseHeaders(HttpHeaders responseHeaders, HttpStatusCode statusCode) {
    initializeHeaderFilters();
    HttpHeaders headers = new HttpHeaders();
    headers.putAll(responseHeaders);
    ServerResponse responseMetadata = new ResponseMetadata(statusCode);
    for (ResponseHttpHeadersFilter filter : this.responseHeaderFilters) {
      headers = filter.apply(headers, responseMetadata);
    }
    return headers;
  }

  private CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> applyHeaderTimeout(
      CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> upstream) {
    CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> timed =
        upstream.orTimeout(this.headerTimeout.toMillis(), TimeUnit.MILLISECONDS);
    timed.whenComplete(
        (ignored, error) -> {
          if (error != null && contains(error, TimeoutException.class)) {
            upstream.cancel(true);
          }
        });
    return timed;
  }

  private void initializeHeaderFilters() {
    if (!this.requestHeaderFilters.isEmpty() || !this.responseHeaderFilters.isEmpty()) {
      return;
    }
    synchronized (this) {
      if (this.requestHeaderFilters.isEmpty() && this.responseHeaderFilters.isEmpty()) {
        this.requestHeaderFilters = this.requestHeaderFiltersProvider.orderedStream().toList();
        this.responseHeaderFilters = this.responseHeaderFiltersProvider.orderedStream().toList();
      }
    }
  }

  private void release(AtomicBoolean released) {
    if (released.compareAndSet(false, true)) {
      this.connections.release();
    }
  }

  private static boolean restrictedRequestHeader(String name) {
    return switch (name.toLowerCase(Locale.ROOT)) {
      case "connection", "content-length", "expect", "host", "transfer-encoding", "upgrade" -> true;
      default -> false;
    };
  }

  private static boolean contains(Throwable error, Class<? extends Throwable> type) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (type.isInstance(current)) {
        return true;
      }
    }
    return false;
  }

  private static Duration nonNullDuration(@Nullable Duration configured, Duration fallback) {
    return configured == null ? fallback : configured;
  }

  private static ThreadFactory daemonThreadFactory() {
    return runnable -> {
      Thread thread = new Thread(runnable, "rwms-gateway-sse-http-" + THREAD_SEQUENCE.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  private static final class AsyncSseProxyResponse implements ServerResponse {
    private final CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> upstream;
    private final ResponseHeadersFilter responseHeadersFilter;
    private final GatewayUpstreamProblemWriter upstreamProblems;
    private final Runnable release;

    private AsyncSseProxyResponse(
        CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> upstream,
        ResponseHeadersFilter responseHeadersFilter,
        GatewayUpstreamProblemWriter upstreamProblems,
        Runnable release) {
      this.upstream = upstream;
      this.responseHeadersFilter = responseHeadersFilter;
      this.upstreamProblems = upstreamProblems;
      this.release = release;
    }

    @Override
    public HttpStatusCode statusCode() {
      return HttpStatus.OK;
    }

    @Override
    public HttpHeaders headers() {
      return HttpHeaders.EMPTY;
    }

    @Override
    public MultiValueMap<String, Cookie> cookies() {
      return new LinkedMultiValueMap<>();
    }

    @Override
    public @Nullable ModelAndView writeTo(
        HttpServletRequest servletRequest,
        HttpServletResponse servletResponse,
        Context context)
        throws ServletException, IOException {
      AsyncContext asyncContext = null;
      try {
        asyncContext = servletRequest.startAsync();
        asyncContext.setTimeout(0);
        StreamBridge bridge =
            new StreamBridge(
                asyncContext,
                servletRequest,
                servletResponse,
                this.upstream,
                this.responseHeadersFilter,
                this.upstreamProblems,
                this.release);
        bridge.start();
      } catch (RuntimeException exception) {
        // A browser can disappear between routing and startAsync(). The response will never own
        // the future in that case, so it must release its reserved slot here.
        this.upstream.cancel(true);
        this.release.run();
        if (asyncContext != null) {
          try {
            asyncContext.complete();
          } catch (IllegalStateException ignored) {
            // The container already completed the failed async request.
          }
        }
        throw exception;
      }
      return null;
    }
  }

  static final class StreamBridge implements Flow.Subscriber<List<ByteBuffer>> {
    private final AsyncContext asyncContext;
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> upstream;
    private final ResponseHeadersFilter responseHeadersFilter;
    private final GatewayUpstreamProblemWriter upstreamProblems;
    private final Runnable release;
    private final Object monitor = new Object();
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final AtomicBoolean terminated = new AtomicBoolean();

    private @Nullable ServletOutputStream output;
    private Flow.Subscription subscription;
    private @Nullable byte[] pending;
    private int pendingOffset;
    private boolean upstreamCompleted;
    private boolean responseFlushPending;

    StreamBridge(
        AsyncContext asyncContext,
        HttpServletRequest request,
        HttpServletResponse response,
        CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> upstream,
        ResponseHeadersFilter responseHeadersFilter,
        GatewayUpstreamProblemWriter upstreamProblems,
        Runnable release) {
      this.asyncContext = asyncContext;
      this.request = request;
      this.response = response;
      this.upstream = upstream;
      this.responseHeadersFilter = responseHeadersFilter;
      this.upstreamProblems = upstreamProblems;
      this.release = release;
    }

    void start() {
      this.asyncContext.addListener(
          new AsyncListener() {
            @Override
            public void onComplete(AsyncEvent event) {
              abort();
            }

            @Override
            public void onTimeout(AsyncEvent event) {
              abort();
            }

            @Override
            public void onError(AsyncEvent event) {
              abort();
            }

            @Override
            public void onStartAsync(AsyncEvent event) {
              // The bridge owns one async cycle and does not redispatch it.
            }
          });
      this.upstream.whenComplete(
          (upstreamResponse, error) -> {
            if (error != null) {
              schedule(() -> failBeforeHeaders(error));
            } else {
              schedule(
                  () -> beginStreaming(upstreamResponse),
                  () -> cancelUnsubscribedBody(upstreamResponse.body()));
            }
          });
    }

    private void beginStreaming(HttpResponse<Flow.Publisher<List<ByteBuffer>>> upstreamResponse) {
      Flow.Publisher<List<ByteBuffer>> body = upstreamResponse.body();
      if (this.terminated.get()) {
        cancelUnsubscribedBody(body);
        return;
      }
      boolean subscribeInvoked = false;
      try {
        this.response.setStatus(upstreamResponse.statusCode());
        HttpHeaders upstreamHeaders = new HttpHeaders();
        upstreamResponse.headers().map().forEach(upstreamHeaders::put);
        HttpHeaders headers =
            this.responseHeadersFilter.apply(
                HttpHeaders.readOnlyHttpHeaders(upstreamHeaders),
                HttpStatusCode.valueOf(upstreamResponse.statusCode()));
        headers.forEach(
            (name, values) -> {
              for (String value : values) {
                this.response.addHeader(name, value);
              }
            });
        this.output = this.response.getOutputStream();
        this.output.setWriteListener(
            new WriteListener() {
              @Override
              public void onWritePossible() {
                scheduleDrain();
              }

              @Override
              public void onError(Throwable error) {
                abort();
              }
            });
        subscribeInvoked = true;
        body.subscribe(this);
      } catch (IOException | RuntimeException exception) {
        if (!subscribeInvoked) {
          cancelUnsubscribedBody(body);
        }
        abort();
      }
    }

    private void failBeforeHeaders(Throwable error) {
      if (this.terminated.compareAndSet(false, true)) {
        try {
          if (!this.response.isCommitted()) {
            this.upstreamProblems.write(error, this.request, this.response);
          }
        } catch (IOException ignored) {
          // The browser may have disconnected before the sanitized problem was written.
        } finally {
          this.release.run();
          complete();
        }
      }
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      synchronized (this.monitor) {
        if (this.terminated.get()) {
          subscription.cancel();
          return;
        }
        this.subscription = subscription;
      }
      subscription.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> item) {
      byte[] copy = copy(item);
      synchronized (this.monitor) {
        if (this.terminated.get()) {
          return;
        }
        this.pending = copy;
        this.pendingOffset = 0;
      }
      scheduleDrain();
    }

    @Override
    public void onError(Throwable error) {
      finishAfterUpstreamError();
    }

    @Override
    public void onComplete() {
      synchronized (this.monitor) {
        this.upstreamCompleted = true;
      }
      scheduleDrain();
    }

    private void scheduleDrain() {
      if (this.terminated.get() || !this.drainScheduled.compareAndSet(false, true)) {
        return;
      }
      schedule(this::drain);
    }

    private void drain() {
      try {
        ServletOutputStream currentOutput = this.output;
        if (currentOutput == null) {
          return;
        }
        while (!this.terminated.get() && currentOutput.isReady()) {
          if (!flushPendingResponse(currentOutput)) {
            return;
          }
          byte[] current;
          int offset;
          int length;
          boolean complete;
          synchronized (this.monitor) {
            current = this.pending;
            if (current == null) {
              complete = this.upstreamCompleted;
              if (!complete) {
                return;
              }
              offset = 0;
              length = 0;
            } else {
              complete = false;
              offset = this.pendingOffset;
              length = Math.min(WRITE_CHUNK_SIZE, current.length - offset);
            }
          }
          if (complete) {
            terminate(false);
            return;
          }

          currentOutput.write(current, offset, length);
          boolean requestNext = false;
          boolean flush = false;
          synchronized (this.monitor) {
            this.pendingOffset += length;
            if (this.pendingOffset == current.length) {
              this.pending = null;
              this.pendingOffset = 0;
              requestNext = !this.upstreamCompleted;
              this.responseFlushPending = true;
              flush = true;
            }
          }
          boolean flushed = !flush || flushPendingResponse(currentOutput);
          if (requestNext) {
            Flow.Subscription currentSubscription;
            synchronized (this.monitor) {
              currentSubscription = this.subscription;
            }
            if (currentSubscription != null) {
              currentSubscription.request(1);
            }
          }
          if (!flushed) {
            return;
          }
        }
      } catch (IOException | RuntimeException exception) {
        abort();
      } finally {
        this.drainScheduled.set(false);
        if (shouldScheduleAnotherDrain()) {
          scheduleDrain();
        }
      }
    }

    private boolean shouldScheduleAnotherDrain() {
      if (this.terminated.get()) {
        return false;
      }
      ServletOutputStream currentOutput = this.output;
      if (currentOutput == null || !currentOutput.isReady()) {
        return false;
      }
      synchronized (this.monitor) {
        return this.pending != null || this.upstreamCompleted || this.responseFlushPending;
      }
    }

    private boolean flushPendingResponse(ServletOutputStream currentOutput) throws IOException {
      synchronized (this.monitor) {
        if (!this.responseFlushPending) {
          return true;
        }
      }
      if (!currentOutput.isReady()) {
        return false;
      }
      // Asset and media already send protocol-valid SSE comments every 15 seconds. Flush each
      // complete upstream item so small comments cross Tomcat/Nginx immediately rather than
      // waiting for the Servlet response buffer to fill. If Tomcat has applied backpressure,
      // leave this flag set and retry from the next onWritePossible callback.
      currentOutput.flush();
      synchronized (this.monitor) {
        this.responseFlushPending = false;
      }
      return true;
    }

    private void terminate(boolean cancelUpstream) {
      if (!this.terminated.compareAndSet(false, true)) {
        return;
      }
      try {
        if (cancelUpstream) {
          this.upstream.cancel(true);
          Flow.Subscription currentSubscription;
          synchronized (this.monitor) {
            currentSubscription = this.subscription;
          }
          if (currentSubscription != null) {
            currentSubscription.cancel();
          }
        }
      } finally {
        this.release.run();
        complete();
      }
    }

    private void finishAfterUpstreamError() {
      if (!this.terminated.compareAndSet(false, true)) {
        return;
      }
      this.release.run();
      complete();
    }

    private void abort() {
      terminate(true);
    }

    private void complete() {
      try {
        this.asyncContext.complete();
      } catch (IllegalStateException ignored) {
        // The container has already completed the request after a client disconnect.
      }
    }

    private void schedule(Runnable task) {
      schedule(task, () -> {});
    }

    private void schedule(Runnable task, Runnable rejected) {
      try {
        this.asyncContext.start(task);
      } catch (IllegalStateException ignored) {
        try {
          rejected.run();
        } finally {
          abort();
        }
      }
    }

    private static void cancelUnsubscribedBody(Flow.Publisher<List<ByteBuffer>> body) {
      try {
        body.subscribe(
            new Flow.Subscriber<>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.cancel();
              }

              @Override
              public void onNext(List<ByteBuffer> ignored) {
                // No body item may be delivered after cancellation.
              }

              @Override
              public void onError(Throwable ignored) {
                // The client is already gone.
              }

              @Override
              public void onComplete() {
                // The client is already gone.
              }
            });
      } catch (RuntimeException ignored) {
        // The upstream publisher failed before it could expose a subscription. The connection is
        // already abandoned and the reserved client slot was released by abort().
      }
    }

    private static byte[] copy(Collection<ByteBuffer> buffers) {
      int size = 0;
      for (ByteBuffer buffer : buffers) {
        size = Math.addExact(size, buffer.remaining());
      }
      byte[] copy = new byte[size];
      int offset = 0;
      for (ByteBuffer buffer : buffers) {
        ByteBuffer duplicate = buffer.duplicate();
        int length = duplicate.remaining();
        duplicate.get(copy, offset, length);
        offset += length;
      }
      return copy;
    }
  }

  @FunctionalInterface
  interface ResponseHeadersFilter {
    HttpHeaders apply(HttpHeaders headers, HttpStatusCode statusCode);
  }

  private static final class ResponseMetadata implements ServerResponse {
    private final HttpStatusCode statusCode;

    private ResponseMetadata(HttpStatusCode statusCode) {
      this.statusCode = statusCode;
    }

    @Override
    public HttpStatusCode statusCode() {
      return this.statusCode;
    }

    @Override
    public HttpHeaders headers() {
      return HttpHeaders.EMPTY;
    }

    @Override
    public MultiValueMap<String, Cookie> cookies() {
      return new LinkedMultiValueMap<>();
    }

    @Override
    public @Nullable ModelAndView writeTo(
        HttpServletRequest request, HttpServletResponse response, Context context) {
      throw new UnsupportedOperationException("Response metadata is not writable");
    }
  }
}
