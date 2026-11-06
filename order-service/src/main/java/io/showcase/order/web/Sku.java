package io.showcase.order.web;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A COMPOSED CONSTRAINT: a reusable, named validation rule built from existing ones. No validator
 * class is needed ({@code validatedBy = {}}).
 *
 * <p>{@code @Sku} documents intent better than repeating {@code @NotBlank @Pattern(regexp = ...)}
 * everywhere, and the rule changes in ONE place. For logic that annotations can't express (a database
 * lookup, cross-field rules), implement a {@code ConstraintValidator}.
 */
@Documented
@NotBlank
@Pattern(regexp = "SKU-[A-Z0-9-]{1,40}")
@ReportAsSingleViolation          // one clear message instead of one per composed constraint
@Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Sku {

    String message() default "must be a valid SKU (SKU-XXXX)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
