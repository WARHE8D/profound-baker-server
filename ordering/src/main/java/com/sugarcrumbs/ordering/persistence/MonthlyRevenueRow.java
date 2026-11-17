package com.sugarcrumbs.ordering.persistence;

import java.math.BigDecimal;

/** One month of {@link InvoiceRepository#revenueByMonth}: {@code month} is {@code YYYY-MM}. */
public interface MonthlyRevenueRow {

    String getMonth();

    BigDecimal getRevenue();

    Long getInvoices();
}
