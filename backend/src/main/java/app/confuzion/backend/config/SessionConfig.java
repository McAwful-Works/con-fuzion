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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.session.MapSession;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.session.data.redis.RedisSessionMapper;

/**
 * Spring Session in Valkey. Boot configures the indexed repository from
 * {@code application.properties}; this class only makes it survive damaged
 * session hashes (#44).
 * <p>
 * A request saves only what it changed, with {@code HSET}, and {@code HSET}
 * creates a key that isn't there. So when a request is still in flight while
 * sign-in rotates its session ID (the old key is renamed away), its save
 * recreates the old key with just {@code lastAccessedTime}. Spring Session's
 * default mapper then throws on every read of that key, and the browser gets a
 * 500 until the key expires. The save is a series of separate commands in
 * Spring Session's private code, so it can't be made conditional here. Instead,
 * a damaged hash is deleted on first read and treated as no session. That is
 * safe, because a recreated hash never has {@code creationTime} (only a new
 * session writes it), so it can never be read back as a signed-in session.
 *
 * @author mcawful
 */
@Configuration(proxyBeanMethods = false)
public class SessionConfig {
	/**
	 * Installs the {@link DamagedSessionMapper} on the session repository.
	 *
	 * @param namespace the session key namespace, the same property Boot applies to
	 *                  the repository
	 * @return the {@link SessionRepositoryCustomizer}
	 */
	@Bean
	SessionRepositoryCustomizer<RedisIndexedSessionRepository> damagedSessionCleanup(
			@Value("${spring.session.data.redis.namespace:spring:session}") String namespace) {
		return repository -> repository
				.setRedisSessionMapper(new DamagedSessionMapper(repository.getSessionRedisOperations(), namespace));
	}

	/**
	 * Maps a stored session hash to a session like Spring Session's own
	 * {@link RedisSessionMapper}, except that a hash missing a required field is
	 * deleted, with its expiry key, and mapped to {@code null}. The repository
	 * reads {@code null} as no session, so {@code findById} returns nothing and
	 * {@code findByPrincipalName} skips it.
	 * <p>
	 * It deletes the keys itself, because the repository's {@code deleteById} reads
	 * the session through this mapper again. The session's ID can stay in its
	 * principal-name index, where lookups skip it like any expired ID. The race
	 * only damages pre-login sessions, which aren't indexed.
	 *
	 * @author mcawful
	 */
	static final class DamagedSessionMapper implements BiFunction<String, Map<String, Object>, MapSession> {
		/**
		 * The hash fields {@link RedisSessionMapper} can't do without.
		 */
		static final List<String> REQUIRED_FIELDS = List.of("creationTime", "lastAccessedTime", "maxInactiveInterval");

		/**
		 * The {@link Logger}.
		 */
		private static final Logger LOGGER = LoggerFactory.getLogger(DamagedSessionMapper.class);

		/**
		 * Spring Session's mapper, for hashes that are whole.
		 */
		private final RedisSessionMapper delegate = new RedisSessionMapper();

		/**
		 * The repository's Valkey operations.
		 */
		private final RedisOperations<String, Object> redisOperations;

		/**
		 * The prefix of a session hash key, such as {@code spring:session:sessions:}.
		 */
		private final String sessionKeyPrefix;

		/**
		 * Creates the mapper.
		 *
		 * @param redisOperations the repository's Valkey operations
		 * @param namespace       the session key namespace
		 */
		DamagedSessionMapper(RedisOperations<String, Object> redisOperations, String namespace) {
			this.redisOperations = redisOperations;
			// Mirrors the repository's own (package-private) key layout in Spring Session
			// 4.2: <namespace>:sessions:<id> and <namespace>:sessions:expires:<id>.
			this.sessionKeyPrefix = namespace + ":sessions:";
		}

		/**
		 * Maps a session hash, or deletes it and returns {@code null} when it is
		 * damaged.
		 *
		 * @param sessionId the session ID
		 * @param map       the session hash
		 * @return the {@link MapSession}, or {@code null} for a damaged hash
		 */
		@Override
		public MapSession apply(String sessionId, Map<String, Object> map) {
			var missing = REQUIRED_FIELDS.stream().filter(field -> map.get(field) == null).toList();
			if (missing.isEmpty())
				return delegate.apply(sessionId, map);
			redisOperations.delete(List.of(sessionKeyPrefix + sessionId, sessionKeyPrefix + "expires:" + sessionId));
			LOGGER.warn("Deleted damaged session {} (missing {}); the request continues without a session",
					fingerprint(sessionId), missing);
			return null;
		}

		/**
		 * Returns a short, one-way fingerprint of a session ID, so log lines about the
		 * same session can be matched without the log ever holding a usable ID.
		 *
		 * @param sessionId the session ID
		 * @return the first 12 hex digits of the ID's SHA-256
		 */
		static String fingerprint(String sessionId) {
			try {
				var digest = MessageDigest.getInstance("SHA-256").digest(sessionId.getBytes(StandardCharsets.UTF_8));
				return HexFormat.of().formatHex(digest, 0, 6);
			} catch (NoSuchAlgorithmException e) {
				throw new IllegalStateException("SHA-256 is required by the Java platform", e);
			}
		}
	}
}
