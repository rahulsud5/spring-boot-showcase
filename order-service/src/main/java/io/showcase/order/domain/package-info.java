/**
 * The DOMAIN layer: entities, value objects and business rules. Plain Java + JPA mapping annotations,
 * with NO Spring web, messaging or infrastructure imports (enforced by ArchitectureTest).
 *
 * <p>{@code @NullMarked} (JSpecify, adopted portfolio-wide by Spring Framework 7): inside this package,
 * every type is NON-NULL unless annotated {@code @Nullable}. Tools such as NullAway or IntelliJ then
 * report possible NullPointerExceptions at COMPILE time. Note that the annotation applies to this
 * package only, not to sub-packages, because Java packages aren't hierarchical.
 */
@NullMarked
package io.showcase.order.domain;

import org.jspecify.annotations.NullMarked;
