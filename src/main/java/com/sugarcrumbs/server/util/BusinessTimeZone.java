package com.sugarcrumbs.server.util;

import java.time.ZoneId;

/**
 * The single timezone this business operates in, used anywhere an
 * {@link java.time.Instant} (booking times, etc.) needs to be read as a
 * local date/time — e.g. to match a {@code Booking.scheduledFor} against
 * a {@code WorkingHours} slot defined in {@link java.time.LocalTime}.
 *
 * <p><b>Known simplification:</b> this is hardcoded to the server's own
 * zone for now. A single-owner bakery only has one timezone that
 * matters, so this is correct in practice, but it's fragile — if the
 * app is ever deployed somewhere whose system zone doesn't match the
 * bakery's actual location, slot math silently shifts. The real fix,
 * when Sprint 1 (Owner profile) is revisited, is a {@code timeZone}
 * field on {@code Owner} and reading it from there instead of this
 * constant.
 */
public final class BusinessTimeZone {

    public static final ZoneId ZONE = ZoneId.systemDefault();

    private BusinessTimeZone() {
    }
}
