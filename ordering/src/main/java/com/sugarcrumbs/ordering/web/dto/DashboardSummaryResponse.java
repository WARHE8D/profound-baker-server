package com.sugarcrumbs.ordering.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * The Owner's dashboard at a glance (#23).
 *
 * @param today          today in the bakery's time zone, the day {@code todaysPickups} is about
 * @param pendingOrders  orders waiting for the Owner to accept or reject, whatever their pickup day
 * @param revenueByMonth oldest month first, every month in the range, months without a payment at zero
 */
public record DashboardSummaryResponse(
        LocalDate today,
        TodaysPickups todaysPickups,
        long pendingOrders,
        List<MonthlyRevenue> revenueByMonth
) {

    /**
     * @param total     accepted orders picked up today: still to collect, plus already collected
     * @param toCollect of those, the ones not yet collected (confirmed or in preparation)
     */
    public record TodaysPickups(long total, long toCollect) {
    }

    /**
     * @param month        e.g. {@code "2026-11"}, by when the invoice was paid
     * @param revenue      what paid invoices brought in, after discounts; refunded ones don't count
     * @param paidInvoices how many invoices that is
     */
    public record MonthlyRevenue(YearMonth month, BigDecimal revenue, long paidInvoices) {
    }
}
