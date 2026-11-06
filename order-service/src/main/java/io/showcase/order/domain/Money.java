package io.showcase.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * VALUE OBJECT for money: an amount plus a currency, immutable, compared by value.
 *
 * <p>A Java {@code record} used as a JPA {@code @Embeddable} (supported since Hibernate 6.2): the
 * columns live in the owning table ({@code total_amount}, {@code currency}), and Hibernate builds
 * instances through the canonical constructor.
 *
 * <p>Why not a {@code double}? {@code 0.1 + 0.2 != 0.3} in binary floating point. BigDecimal with an
 * explicit scale and rounding mode is the only safe representation for money, together with
 * {@code numeric(12,2)} in the database.
 *
 * <p>{@code @JdbcTypeCode(SqlTypes.CHAR)}: the schema uses a fixed-length {@code char(3)} for ISO codes, but
 * Hibernate maps String to VARCHAR by default. Without this, {@code ddl-auto=validate} fails at startup ("found
 * bpchar, expecting varchar"): validation catching a mapping drift before production does.
 *
 * <p>{@code Serializable}: prices are cached in Redis with JDK serialization (CacheConfig).
 */
@Embeddable
public record Money(
        @Column(name = "amount", nullable = false, precision = 12, scale = 2) BigDecimal amount,
        @Column(name = "currency", nullable = false, length = 3) @JdbcTypeCode(SqlTypes.CHAR) String currency) implements Serializable {

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (currency.length() != 3) {
            throw new IllegalArgumentException("currency must be an ISO-4217 code: " + currency);
        }
        amount = amount.setScale(2, RoundingMode.HALF_EVEN);   // banker's rounding, normalized scale
    }

    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount).max(BigDecimal.ZERO), currency);
    }

    public Money times(int quantity) {
        return new Money(amount.multiply(BigDecimal.valueOf(quantity)), currency);
    }

    public Money percentage(int percent) {
        return new Money(amount.multiply(BigDecimal.valueOf(percent)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN), currency);
    }

    public boolean isGreaterThanOrEqual(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) >= 0;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency;
    }
}
