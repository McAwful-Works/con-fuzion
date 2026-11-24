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

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import app.confuzion.backend.util.HttpUtil;
import lombok.Generated;

/**
 * Logger aspect. Logs one line of request metadata per controller call: HTTP
 * method, route pattern, status, latency and user ID. It never logs bodies,
 * arguments, headers, cookies, tokens or query strings.
 *
 * @author mcawful
 */
@Aspect
@Component
public class LoggerAspect {
	/**
	 * The controller pointcut.
	 */
	@Generated
	@Pointcut("within(app.confuzion.backend.controller.*)")
	public void controllerPointcut() {
		// This method is intentionally left empty
	}

	/**
	 * Around advice logger for controller methods.
	 * <p>
	 * The path is the matched route pattern (for example {@code /api/events/{id}}),
	 * not the request URI, so IDs and tokens in the path never reach the log. When
	 * the controller throws, the exception type is logged in place of the status;
	 * {@link GlobalControllerExceptionHandler} logs the status and details under a
	 * correlation ID.
	 *
	 * @param pjp the {@link ProceedingJoinPoint}
	 * @return the result of proceeding
	 * @throws Throwable When the invoked proceed throws anything
	 */
	@Around("controllerPointcut()")
	public Object logAroundController(ProceedingJoinPoint pjp) throws Throwable {
		var logger = LoggerFactory.getLogger(pjp.getTarget().getClass());
		var attributes = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
		var request = attributes.getRequest();
		var start = System.nanoTime();
		var outcome = "threw";
		try {
			var result = pjp.proceed();
			outcome = String.valueOf(getStatus(result, attributes));
			return result;
		} catch (Throwable t) {
			outcome = "threw " + t.getClass().getSimpleName();
			throw t;
		} finally {
			logger.info("{} {} -> {} in {} ms (user {})", request.getMethod(),
					Objects.toString(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE), "unknown"),
					outcome, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
					HttpUtil.getUserId(request).orElse("anonymous"));
		}
	}

	/**
	 * Returns the HTTP status of a controller result: the status of a returned
	 * {@link ResponseEntity}, otherwise the status already set on the response.
	 *
	 * @param result     the controller method's return value
	 * @param attributes the current {@link ServletRequestAttributes}
	 * @return the HTTP status code
	 */
	private static int getStatus(Object result, ServletRequestAttributes attributes) {
		if (result instanceof ResponseEntity<?> entity)
			return entity.getStatusCode().value();
		var response = attributes.getResponse();
		return response == null ? 200 : response.getStatus();
	}
}
