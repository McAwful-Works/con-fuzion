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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Supplier;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import app.confuzion.backend.exception.RateLimitException;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;

/**
 * Unit tests for the {@link RateLimiterAspect}: bucket key selection and
 * allow/deny. {@code RateLimiterAspectIntegrationTest} covers real buckets.
 *
 * @author mcawful
 */
@ExtendWith(MockitoExtension.class)
class RateLimiterAspectTest {
	/**
	 * The mock {@link ProceedingJoinPoint}.
	 */
	@Mock
	private ProceedingJoinPoint proceedingJoinPoint;

	/**
	 * The mock {@link ProxyManager}.
	 */
	@Mock
	private ProxyManager<String> proxyManager;

	/**
	 * The mock {@link BucketProxy}.
	 */
	@Mock
	private BucketProxy bucket;

	/**
	 * The current request.
	 */
	private MockHttpServletRequest request;

	/**
	 * The {@link RateLimiterAspect} to test.
	 */
	private RateLimiterAspect rateLimiterAspect;

	/**
	 * Binds a request to the thread and creates the aspect.
	 */
	@BeforeEach
	void setup() {
		request = new MockHttpServletRequest();
		request.setRemoteAddr("198.51.100.1");
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
		rateLimiterAspect = new RateLimiterAspect(proxyManager, 10, 5, 1);
	}

	/**
	 * Unbinds the request.
	 */
	@AfterEach
	void tearDown() {
		RequestContextHolder.resetRequestAttributes();
	}

	/**
	 * Tests that an authenticated request uses the user's bucket, configured from
	 * the rate-limiter properties, and proceeds when a token is available.
	 */
	@Test
	@SuppressWarnings("unchecked")
	void testRateLimitAllowsUserRequest() throws Throwable {
		request.setUserPrincipal(() -> "alice");
		var configuration = ArgumentCaptor.forClass(Supplier.class);
		when(proxyManager.getProxy(eq("con-fuzion:rate-limit:user:alice"), configuration.capture())).thenReturn(bucket);
		when(bucket.tryConsume(1)).thenReturn(true);
		when(proceedingJoinPoint.proceed()).thenReturn("Success");

		assertThat(rateLimiterAspect.rateLimit(proceedingJoinPoint)).isEqualTo("Success");
		var bandwidth = ((BucketConfiguration) configuration.getValue().get()).getBandwidths()[0];
		assertThat(bandwidth.getCapacity()).isEqualTo(10);
		assertThat(bandwidth.getRefillTokens()).isEqualTo(5);
	}

	/**
	 * Tests that an anonymous request falls back to the per-IP bucket.
	 */
	@Test
	void testRateLimitUsesIpBucketForAnonymousRequest() throws Throwable {
		when(proxyManager.getProxy(eq("con-fuzion:rate-limit:ip:198.51.100.1"), any())).thenReturn(bucket);
		when(bucket.tryConsume(1)).thenReturn(true);

		rateLimiterAspect.rateLimit(proceedingJoinPoint);

		verify(proceedingJoinPoint).proceed();
	}

	/**
	 * Tests that an empty bucket throws {@link RateLimitException} without
	 * proceeding.
	 */
	@Test
	void testRateLimitBlocksRequest() throws Throwable {
		when(proxyManager.getProxy(any(), any())).thenReturn(bucket);
		when(bucket.tryConsume(1)).thenReturn(false);

		assertThatThrownBy(() -> rateLimiterAspect.rateLimit(proceedingJoinPoint))
				.isInstanceOf(RateLimitException.class);
		verify(proceedingJoinPoint, never()).proceed();
	}
}
