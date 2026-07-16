package dev.buhanzaz.rwms.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void preservesCanonicalUuidCorrelationId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "7f1bcf5c-2b33-4f71-9d6d-fbc77fe8dc90");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME))
                .isEqualTo("7f1bcf5c-2b33-4f71-9d6d-fbc77fe8dc90");
        assertThat(request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE))
                .isEqualTo("7f1bcf5c-2b33-4f71-9d6d-fbc77fe8dc90");
    }

    @Test
    void replacesUnsafeCallerValue() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "request-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME))
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void exposesCorrelationIdInMdcAndRestoresPreviousValue() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "7f1bcf5c-2b33-4f71-9d6d-fbc77fe8dc90");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> observed = new AtomicReference<>();
        MDC.put(CorrelationIdFilter.MDC_KEY, "outer-request");

        try {
            filter.doFilter(request, response, (currentRequest, currentResponse) ->
                    observed.set(MDC.get(CorrelationIdFilter.MDC_KEY)));

            assertThat(observed).hasValue("7f1bcf5c-2b33-4f71-9d6d-fbc77fe8dc90");
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("outer-request");
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    @Test
    void clearsMdcEvenWhenDownstreamFails() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MDC.remove(CorrelationIdFilter.MDC_KEY);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> filter.doFilter(
                        request,
                        response,
                        (currentRequest, currentResponse) -> {
                            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNotBlank();
                            throw new IllegalStateException("downstream failure");
                        }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
