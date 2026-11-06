package io.showcase.order.persistence;

import io.showcase.order.domain.OrderStatus;
import java.math.BigDecimal;

/** DTO projection target of {@code OrderRepository.countByStatusSince}: built by a JPQL constructor expression. */
public record StatusCount(OrderStatus status, long orders, BigDecimal revenue) {
}
