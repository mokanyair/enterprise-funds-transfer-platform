package com.enterprise.funds.transfer.domain;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Supported currencies and their minor-unit scale (decision D4: USD only, 2 decimals). Money is always
 * BigDecimal; there are no floats anywhere in the service.
 */
public final class CurrencyPolicy {

    private static final Map<String, Integer> MINOR_UNITS = Map.of("USD", 2);

    private CurrencyPolicy() {}

    public static boolean isSupported(String currency) {
        return currency != null && MINOR_UNITS.containsKey(currency);
    }

    public static int minorUnits(String currency) {
        Integer units = MINOR_UNITS.get(currency);
        if (units == null) {
            throw new IllegalArgumentException("Unsupported currency: " + currency);
        }
        return units;
    }

    /** Rejects amounts with more decimal places than the currency allows (400). Trailing zeros are fine. */
    public static void requireScale(String currency, BigDecimal amount) {
        if (amount.stripTrailingZeros().scale() > minorUnits(currency)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST)
                    .detail("amount", "at most " + minorUnits(currency) + " decimal places for " + currency);
        }
    }

    /** Formats an amount at the currency's minor-unit scale, e.g. "250.00" for USD. */
    public static String format(String currency, BigDecimal amount) {
        return amount.setScale(minorUnits(currency), java.math.RoundingMode.UNNECESSARY).toPlainString();
    }
}
