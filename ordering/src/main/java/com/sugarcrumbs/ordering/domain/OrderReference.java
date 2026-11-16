package com.sugarcrumbs.ordering.domain;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * Human-readable order references such as {@code SC-7F3K2}: what the Owner and
 * the guest say on the phone, so no lookalike characters (Crockford base32: no
 * I, L, O or U). Five characters give 33.5 million values; uniqueness is
 * enforced by the database, and the service retries on the rare clash.
 *
 * <p>A reference is not a secret and never grants access: guests get a separate
 * magic-link token (#18).
 */
public final class OrderReference {

    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int LENGTH = 5;
    private static final String PREFIX = "SC-";

    /** New references, plus {@code SC-L0001}-style ones given to bookings migrated from before orders existed. */
    public static final Pattern FORMAT = Pattern.compile("SC-([0-9A-HJKMNP-TV-Z]{5}|L\\d{4,})");

    private static final RandomGenerator RANDOM = new SecureRandom();

    private OrderReference() {
    }

    public static String next() {
        return next(RANDOM);
    }

    static String next(RandomGenerator random) {
        StringBuilder reference = new StringBuilder(PREFIX);
        for (int i = 0; i < LENGTH; i++) {
            reference.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return reference.toString();
    }
}
