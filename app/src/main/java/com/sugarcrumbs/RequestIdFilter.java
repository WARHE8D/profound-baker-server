package com.sugarcrumbs;

import io.micrometer.common.KeyValue;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.filter.ServerHttpObservationFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every request an id (#26): the caller's {@code X-Request-Id} when it is a plausible one, a
 * new UUID otherwise. It is returned in the response's {@code X-Request-Id}, put in the logging MDC
 * as {@code requestId} for every line logged while handling the request, and added to the request's
 * trace span. A guest or the SPA reporting "it failed, request id abc" leads straight to the log
 * lines and the trace.
 *
 * <p>Runs right after Spring's observation filter (which opens the span) and before rate limiting
 * and security, so a 429 or 401 carries an id too. An incoming id is only reused when it is short
 * and plain ({@link #ACCEPTED}): it ends up in log lines, and must not be able to forge one.
 */
class RequestIdFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Request-Id";
    static final String MDC_KEY = "requestId";

    /** Letters, digits, dot, dash and underscore; UUIDs and most tracing ids fit. */
    private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9._-]{8,64}");
    private static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = requestId(request);
        response.setHeader(HEADER, id);
        ServerHttpObservationFilter.findObservationContext(request)
                .ifPresent(context -> context.addHighCardinalityKeyValue(KeyValue.of("request.id", id)));
        MDC.put(MDC_KEY, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** An error page rendered for this request logs under the same id, not a new one. */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    private static String requestId(HttpServletRequest request) {
        if (request.getAttribute(ATTRIBUTE) instanceof String existing) {
            return existing;
        }
        String incoming = request.getHeader(HEADER);
        String id = incoming != null && ACCEPTED.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, id);
        return id;
    }
}
