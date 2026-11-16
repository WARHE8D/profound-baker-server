package com.sugarcrumbs.ordering.domain;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

class OrderReferenceTests {

    @RepeatedTest(20)
    void referencesLookLikeSC7F3K2AndAvoidLookalikeCharacters() {
        String reference = OrderReference.next();

        assertThat(reference).matches(OrderReference.FORMAT).hasSize(8).startsWith("SC-");
        assertThat(reference.substring(3)).doesNotContain("I", "L", "O", "U");
    }

    @Test
    void referencesAreSpreadOverTheWholeSpace() {
        SplittableRandom random = new SplittableRandom(42);
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < 10_000; i++) {
            seen.add(OrderReference.next(random));
        }

        // 10k draws from 33.5M values: a handful of clashes at most; the service retries those.
        assertThat(seen).hasSizeGreaterThan(9_990);
    }
}
