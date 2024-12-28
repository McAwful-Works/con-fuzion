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
package app.confuzion.backend.aspect;

import java.time.Duration;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

import app.confuzion.backend.exception.RateLimitException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import lombok.Generated;

/**
 * Rate limiter aspect.
 *
 * @author mcawful
 */
@Aspect
@Component
public class RateLimiterAspect {
	/**
	 * The controller pointcut
	 */
	private static final String CONTROLLER_POINTCUT = "within(app.confuzion.backend.controller.*)";

	/**
	 * The exclude addition for the controller pointcut.
	 */
	private static final String CONTROLLER_POINTCUT_EXCLUDE = "!within(app.confuzion.backend.controller.ErrorControllerMVC)";

	/**
	 * The {@link Bucket} size.
	 */
	private static final Bandwidth BANDWIDTH = Bandwidth.builder().capacity(25)
			.refillIntervally(25, Duration.ofSeconds(5)).build();

	/**
	 * The rate limit {@link Bucket}.
	 */
	private static final Bucket BUCKET = Bucket.builder().addLimit(BANDWIDTH).build();

	/**
	 * The controller pointcut.
	 */
	@Generated
	@Pointcut(CONTROLLER_POINTCUT + " && " + CONTROLLER_POINTCUT_EXCLUDE)
	public void controllerPointcut() {
		// This method is intentionally left empty
	}

	/**
	 * Rate limits the API controller endpoints.
	 *
	 * @param pjp the {@link ProceedingJoinPoint}
	 * @return the result of proceeding
	 * @throws Throwable          When the invoked proceed throws anything
	 * @throws RateLimitException When the API endpoint method is rate limited.
	 */
	@Around("controllerPointcut()")
	public Object rateLimit(ProceedingJoinPoint pjp) throws Throwable {
		if (BUCKET.tryConsume(1))
			return pjp.proceed();
		throw new RateLimitException();
	}
}
