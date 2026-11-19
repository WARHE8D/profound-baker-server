package com.sugarcrumbs;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;

/**
 * The routes that are treated differently from the default (Owner only, not rate-limited), in one place:
 * {@link SecurityConfig} permits {@link #PUBLIC}, {@link RateLimitFilter} throttles the rate-limited ones,
 * and {@link OpenApiConfig} documents both. Keeping a single list means the spec can't claim a route is
 * public while security still demands a token, or the other way round.
 */
final class ApiRoutes {

    static final Route LOGIN = new Route(HttpMethod.POST, "/api/auth/login");
    static final Route CHECKOUT = new Route(HttpMethod.POST, "/api/public/orders");
    /** Any method: viewing and cancelling share one bucket, so probing for tokens costs the same either way. */
    static final Route MAGIC_LINK = new Route(null, "/api/public/orders/by-token/**");

    /** Reachable without a token. Everything else needs the Owner (default deny). */
    static final List<Route> PUBLIC = List.of(
            LOGIN,
            new Route(HttpMethod.POST, "/api/auth/refresh"),
            new Route(HttpMethod.GET, "/.well-known/jwks.json"),
            // The shopper surface: public reads, and guest checkout as its only write.
            new Route(HttpMethod.GET, "/api/public/**"),
            CHECKOUT,
            // The guest's own order: the magic-link token in the path is the credential.
            new Route(HttpMethod.POST, "/api/public/orders/by-token/*/cancel"),
            // Image files (Caddy serves them directly in prod) and API docs.
            new Route(HttpMethod.GET, "/uploads/**"),
            new Route(HttpMethod.GET, "/v3/api-docs/**"),
            new Route(HttpMethod.GET, "/swagger-ui/**"),
            new Route(HttpMethod.GET, "/swagger-ui.html"),
            // Actuator only listens on the internal management port (never proxied), and
            // exposes only health, info and prometheus there.
            new Route(null, "/actuator/**"),
            // Error dispatches carry the original request's status and ApiError body.
            new Route(null, "/error"));

    private ApiRoutes() {
    }

    static boolean isPublic(HttpMethod method, String path) {
        return PUBLIC.stream().anyMatch(route -> route.matches(method, path));
    }

    static boolean isRateLimited(HttpMethod method, String path) {
        return List.of(LOGIN, CHECKOUT, MAGIC_LINK).stream().anyMatch(route -> route.matches(method, path));
    }

    /**
     * A Spring path pattern, for one method or ({@code null}) all of them.
     */
    record Route(@Nullable HttpMethod method, String pattern) {

        /**
         * For the spec, whose paths are templates such as {@code /api/orders/{id}}: a template variable is
         * just one more path segment, so {@code *} and {@code **} match it the way they match a real id.
         */
        boolean matches(HttpMethod method, String path) {
            PathPattern parsed = PathPatternParser.defaultInstance.parse(pattern);
            return (this.method == null || this.method.equals(method))
                    && parsed.matches(PathContainer.parsePath(path));
        }
    }
}
