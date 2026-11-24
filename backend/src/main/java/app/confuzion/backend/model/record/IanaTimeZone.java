/**
 * MIT License
 * <p>
 * Copyright (c) 2024 McAwful-Works
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package app.confuzion.backend.model.record;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.ZoneId;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * Bean Validation constraint: the string is an IANA time zone ID that the JDK
 * knows, such as {@code America/Chicago}. {@code null} counts as valid, so add
 * {@code @NotNull} where a value is required.
 *
 * @author mcawful
 */
@Documented
@Constraint(validatedBy = IanaTimeZone.Validator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface IanaTimeZone {
	/**
	 * The longest ID accepted. The longest IANA ID is 32 characters, and
	 * {@code app_user.time_zone} is {@code varchar(64)}.
	 */
	int MAX_LENGTH = 64;

	/**
	 * The violation message.
	 *
	 * @return the message
	 */
	String message() default "must be an IANA time zone ID";

	/**
	 * The validation groups.
	 *
	 * @return the groups
	 */
	Class<?>[] groups() default {};

	/**
	 * The payload.
	 *
	 * @return the payload
	 */
	Class<? extends Payload>[] payload() default {};

	/**
	 * Checks the value against the JDK's region IDs.
	 *
	 * @author mcawful
	 */
	class Validator implements ConstraintValidator<IanaTimeZone, String> {
		/**
		 * Returns whether the value is {@code null} or a known IANA time zone ID.
		 *
		 * @param value   the value
		 * @param context the {@link ConstraintValidatorContext}
		 * @return {@code true} when the value is valid
		 */
		@Override
		public boolean isValid(String value, ConstraintValidatorContext context) {
			return value == null || value.length() <= MAX_LENGTH && ZoneId.getAvailableZoneIds().contains(value);
		}
	}
}
