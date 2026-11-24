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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.RedisOperations;

import app.confuzion.backend.config.SessionConfig.DamagedSessionMapper;

/**
 * Unit tests for {@link SessionConfig.DamagedSessionMapper}.
 *
 * @author mcawful
 */
@ExtendWith(OutputCaptureExtension.class)
class SessionConfigTest {
	/**
	 * A session ID.
	 */
	private static final String SESSION_ID = "0f5e7c1a-3b2d-4e6f-8a9b-1c2d3e4f5a6b";

	/**
	 * The mocked Valkey operations.
	 */
	@SuppressWarnings("unchecked")
	private final RedisOperations<String, Object> redisOperations = mock(RedisOperations.class);

	/**
	 * The mapper to test, on a custom namespace.
	 */
	private final DamagedSessionMapper mapper = new DamagedSessionMapper(redisOperations, "test:ns");

	/**
	 * Tests that a whole hash maps to the session and deletes nothing.
	 */
	@Test
	void testWholeHashIsMapped() {
		var session = mapper.apply(SESSION_ID, whole());

		assertThat(session).isNotNull();
		assertThat(session.getId()).isEqualTo(SESSION_ID);
		assertThat(session.getMaxInactiveInterval()).isEqualTo(Duration.ofDays(14));
		verify(redisOperations, never()).delete(any(Collection.class));
	}

	/**
	 * Tests that a hash missing any required field is deleted with its expiry key,
	 * maps to {@code null}, and is logged by fingerprint only.
	 *
	 * @param output the captured log output
	 */
	@Test
	void testDamagedHashIsDeletedAndMissing(CapturedOutput output) {
		for (var field : DamagedSessionMapper.REQUIRED_FIELDS) {
			var map = whole();
			map.remove(field);

			assertThat(mapper.apply(SESSION_ID, map)).isNull();
		}

		verify(redisOperations, times(3))
				.delete(List.of("test:ns:sessions:" + SESSION_ID, "test:ns:sessions:expires:" + SESSION_ID));
		assertThat(output).contains("Deleted damaged session " + DamagedSessionMapper.fingerprint(SESSION_ID))
				.contains("missing [creationTime]").doesNotContain(SESSION_ID)
				.doesNotContain(SESSION_ID.substring(0, 8));
	}

	/**
	 * Tests that the fingerprint is short, stable and not a piece of the ID.
	 */
	@Test
	void testFingerprint() {
		var fingerprint = DamagedSessionMapper.fingerprint(SESSION_ID);

		assertThat(fingerprint).hasSize(12).matches("[0-9a-f]+").isEqualTo(DamagedSessionMapper.fingerprint(SESSION_ID))
				.isNotEqualTo(DamagedSessionMapper.fingerprint(SESSION_ID + "x"));
		assertThat(SESSION_ID).doesNotContain(fingerprint);
	}

	/**
	 * Returns a hash with every field Spring Session writes for a new session.
	 *
	 * @return the hash
	 */
	private static Map<String, Object> whole() {
		var map = new HashMap<String, Object>();
		map.put("creationTime", Instant.now().toEpochMilli());
		map.put("lastAccessedTime", Instant.now().toEpochMilli());
		map.put("maxInactiveInterval", (int) Duration.ofDays(14).toSeconds());
		return map;
	}
}
