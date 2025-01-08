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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import app.confuzion.backend.exception.RateLimitException;
import io.github.bucket4j.Bucket;

/**
 * Unit tests for the {@link RateLimiterAspect}.
 * 
 * @author mcawful
 */
@SpringBootTest
class RateLimiterAspectTest {
	/**
	 * The mock {@link ProceedingJoinPoint}.
	 */
	@Mock
	private ProceedingJoinPoint proceedingJoinPoint;

	/**
	 * The {@link RateLimiterAspect} to test.
	 */
	@InjectMocks
	private RateLimiterAspect rateLimiterAspect;

	/**
	 * The mock bucket.
	 */
	private Bucket mockBucket;

	/**
	 * Initializes the mock bucket.
	 */
	@BeforeEach
	void setup() {
		mockBucket = mock(Bucket.class);
		ReflectionTestUtils.setField(rateLimiterAspect, "bucket", mockBucket);
	}

	/**
	 * Tests that the {@code rateLimit} method of the {@link RateLimiterAspect}
	 * allows the request. Asserts the expected response was returned and verifies
	 * the bucket and join point methods were called.
	 */
	@Test
	void testRateLimitAllowsRequest() throws Throwable {
		when(mockBucket.tryConsume(1)).thenReturn(true);
		when(proceedingJoinPoint.proceed()).thenReturn("Success");

		var result = rateLimiterAspect.rateLimit(proceedingJoinPoint);

		assertEquals("Success", result);
		verify(mockBucket, times(1)).tryConsume(1);
		verify(proceedingJoinPoint, times(1)).proceed();
	}

	/**
	 * Tests that the {@code rateLimit} method of the {@link RateLimiterAspect}
	 * blocks the request. Asserts the {@link RateLimitException} was thrown and
	 * verifies the bucket method was called and the join point method was not
	 * called.
	 */
	@Test
	void testRateLimitBlocksRequest() throws Throwable {
		when(mockBucket.tryConsume(1)).thenReturn(false);

		assertThrows(RateLimitException.class, () -> rateLimiterAspect.rateLimit(proceedingJoinPoint));
		verify(mockBucket, times(1)).tryConsume(1);
		verify(proceedingJoinPoint, never()).proceed();
	}
}