package com.cosmetic.scm.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Digits;

/**
 * 数量列是 DECIMAL(18,4)。超出小数位数据库会悄悄四舍五入：0.00001 接口说成功，存进去是 0。
 * 所有录入的数量都要加这个注解。
 */
@Documented
@Constraint(validatedBy = {})
@Digits(integer = 14, fraction = 4)
@ReportAsSingleViolation
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface Qty {

    String message() default "数量最多 14 位整数、4 位小数";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
