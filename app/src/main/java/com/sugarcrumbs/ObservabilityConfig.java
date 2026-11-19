package com.sugarcrumbs;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationView;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Keeps the traces sent to Tempo to what a person would look at (#26).
 *
 * <p>On the API port, Spring Security's filter-chain observations and the database's connection and
 * query observations run inside the request's own {@code http.server.requests} observation, so they
 * appear as children of the request's span; scheduled jobs give theirs a parent the same way. Requests
 * on the management port (the health check and Prometheus scrapes, every 15 seconds each) have no
 * request observation, and a scrape also queries the database for {@code baker_orders_pending}: each
 * would become a trace of its own, thousands of near-empty traces a day. These observations are
 * therefore not recorded when they have no parent; inside a request or a job they still are. A parent
 * that was itself not recorded counts as none: Micrometer keeps a no-op in its place, so without that
 * the query inside a dropped connection would still start a trace.
 */
@Configuration(proxyBeanMethods = false)
class ObservabilityConfig {

    /** Observations that only mean something as part of a request or job. */
    static final List<String> NEVER_A_TRACE_OF_THEIR_OWN = List.of("spring.security.", "jdbc.");

    @Bean
    ObservationPredicate noSecurityOrDatabaseTracesOutsideARequest() {
        return (name, context) -> {
            ObservationView parent = context.getParentObservation();
            boolean recordedParent = parent != null && !(parent instanceof Observation observation && observation.isNoop());
            return recordedParent || NEVER_A_TRACE_OF_THEIR_OWN.stream().noneMatch(name::startsWith);
        };
    }
}
