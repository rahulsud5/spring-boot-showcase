package io.showcase.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Value object: shipping address embedded into the orders table (shipping_* columns). */
@Embeddable
public record Address(
        @Column(name = "shipping_street", nullable = false, length = 200) String street,
        @Column(name = "shipping_city", nullable = false, length = 100) String city,
        @Column(name = "shipping_postal_code", nullable = false, length = 20) String postalCode,
        @Column(name = "shipping_country", nullable = false, length = 2) @JdbcTypeCode(SqlTypes.CHAR) String country) {
}
