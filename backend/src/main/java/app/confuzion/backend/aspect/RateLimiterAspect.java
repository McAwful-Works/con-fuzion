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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import app.confuzion.backend.exception.RateLimitException;
import app.confuzion.backend.util.HttpUtil;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Generated;

/**
 * Rate limiter aspect. Each authenticated user gets their own bucket, and
 * anonymous requests share a bucket per client IP. Buckets live in Valkey, so
 * every app instance enforces the same limit.
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
	 * The Valkey key prefix for rate-limit buckets.
	 */
	private static final String KEY_PREFIX = "con-fuzion:rate-limit:";

	/**
	 * The {@link ProxyManager} that stores the buckets in Valkey.
	 */
	private final ProxyManager<String> proxyManager;

	/**
	 * The configuration every bucket is created with.
	 */
	private final BucketConfiguration bucketConfiguration;

	/**
	 * Creates the aspect.
	 *
	 * @param proxyManager        the {@link ProxyManager} that stores the buckets
	 * @param tokenCapacity       the token capacity for each bucket
	 * @param tokenRefillAmount   the token refill amount for each bucket
	 * @param refillRateInSeconds the bucket refill interval in seconds
	 */
	public RateLimiterAspect(ProxyManager<String> proxyManager,
			@Value("${con-fuzion.rate-limiter.token.capacity:10}") int tokenCapacity,
			@Value("${con-fuzion.rate-limiter.token.refill-amount:5}") int tokenRefillAmount,
			@Value("${con-fuzion.rate-limiter.refill-rate-seconds:1}") int refillRateInSeconds) {
		this.proxyManager = proxyManager;
		var bandwidth = Bandwidth.builder().capacity(tokenCapacity)
				.refillIntervally(tokenRefillAmount, Duration.ofSeconds(refillRateInSeconds)).build();
		this.bucketConfiguration = BucketConfiguration.builder().addLimit(bandwidth).build();
	}

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
	 * @throws RateLimitException When the caller's bucket is empty
	 */
	@Around("controllerPointcut()")
	public Object rateLimit(ProceedingJoinPoint pjp) throws Throwable {
		var request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
		if (proxyManager.getProxy(bucketKey(request), () -> bucketConfiguration).tryConsume(1))
			return pjp.proceed();
		throw new RateLimitException();
	}

	/**
	 * Returns the bucket key for a request: the user ID when the caller is
	 * authenticated, otherwise the client IP.
	 *
	 * @param request the {@link HttpServletRequest}
	 * @return the Valkey key of the caller's bucket
	 */
	private static String bucketKey(HttpServletRequest request) {
		return HttpUtil.getUserId(request).map(userId -> KEY_PREFIX + "user:" + userId)
				.orElseGet(() -> KEY_PREFIX + "ip:" + HttpUtil.getRemoteAddr(request));
	}
}
