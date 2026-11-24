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
package app.confuzion.backend.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import io.lettuce.core.cluster.RedisClusterClient;

/**
 * Unit tests for the {@link RateLimiterConfig} class. The standalone path runs
 * in the integration tests.
 *
 * @author mcawful
 */
class RateLimiterConfigTest {
	/**
	 * Tests that a cluster-mode client fails fast instead of being used wrongly.
	 */
	@Test
	void testRateLimiterProxyManagerRejectsClusterClient() {
		var connectionFactory = mock(LettuceConnectionFactory.class);
		when(connectionFactory.getRequiredNativeClient()).thenReturn(mock(RedisClusterClient.class));

		assertThatThrownBy(() -> new RateLimiterConfig().rateLimiterProxyManager(connectionFactory))
				.isInstanceOf(IllegalStateException.class);
	}
}
