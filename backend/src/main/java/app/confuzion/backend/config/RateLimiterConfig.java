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

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;

/**
 * Configures the distributed rate-limit buckets, which live in Valkey so every
 * app instance shares them.
 *
 * @author mcawful
 */
@Configuration(proxyBeanMethods = false)
public class RateLimiterConfig {
	/**
	 * How long a bucket key stays in Valkey after its bucket would have refilled to
	 * capacity. Idle buckets then expire instead of piling up.
	 */
	private static final Duration KEEP_AFTER_REFILL = Duration.ofSeconds(10);

	/**
	 * Creates the bucket4j {@link ProxyManager} on the Lettuce client that Spring
	 * Boot already configured from {@code spring.data.redis.*}, so the rate limiter
	 * shares its host, credentials and TLS settings with Spring Session. The
	 * manager keeps the client it is built on, so it relies on the factory not
	 * being stopped and restarted while the app serves requests.
	 *
	 * @param connectionFactory the auto-configured {@link LettuceConnectionFactory}
	 * @return a {@link ProxyManager} keyed by strings
	 * @throws IllegalStateException if Valkey is configured in cluster mode, which
	 *                               isn't supported yet
	 */
	@Bean
	public ProxyManager<String> rateLimiterProxyManager(LettuceConnectionFactory connectionFactory) {
		if (!(connectionFactory.getRequiredNativeClient() instanceof RedisClient client))
			throw new IllegalStateException("Rate limiting supports a standalone Valkey connection only");

		return Bucket4jLettuce.casBasedBuilder(client)
				.expirationAfterWrite(
						ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(KEEP_AFTER_REFILL))
				.build().withMapper((String key) -> key.getBytes(StandardCharsets.UTF_8));
	}
}
