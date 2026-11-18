package com.sugarcrumbs;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps the traces sent to Tempo to what a person would look at (#26).
 *
 * <p>On the API port, Spring Security's filter-chain observations run inside the request's own
 * {@code http.server.requests} observation, so they appear as children of the request's span. Requests
 * on the management port (the health check and Prometheus scrapes, every 15 seconds each) have no
 * request observation, so the same security observations would each become a trace of their own:
 * thousands of empty "security filterchain" traces a day. Security observations without a parent are
 * therefore not recorded; inside a request they still are.
 */
@Configuration(proxyBeanMethods = false)
class ObservabilityConfig {

    @Bean
    ObservationPredicate noSecurityTracesOutsideARequest() {
        return (name, context) -> !(name.startsWith("spring.security.") && context.getParentObservation() == null);
    }
}
