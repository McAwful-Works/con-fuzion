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
package app.confuzion.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.session.SessionRepository;

/**
 * Integration smoke test: the full context starts against Postgres and Valkey,
 * and Flyway applies the baseline migration.
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ApplicationContextTest {
	/**
	 * The {@link ApplicationContext} to test.
	 */
	@Autowired
	private ApplicationContext applicationContext;

	/**
	 * The auto-configured {@link Flyway}.
	 */
	@Autowired
	private Flyway flyway;

	/**
	 * The Spring Session {@link SessionRepository}.
	 */
	@Autowired
	private SessionRepository<?> sessionRepository;

	/**
	 * Tests that the context loads and Flyway has applied the baseline migration.
	 */
	@Test
	void contextLoadsAndFlywayAppliesBaseline() {
		assertThat(applicationContext).isNotNull();
		assertThat(flyway.info().applied()).extracting(MigrationInfo::getScript)
				.contains("V202611241200__baseline.sql");
	}

	/**
	 * Tests that sessions are stored by Spring Session with a 14-day idle timeout.
	 */
	@Test
	void sessionsHaveFourteenDayIdleTimeout() {
		assertThat(sessionRepository.createSession().getMaxInactiveInterval()).isEqualTo(Duration.ofDays(14));
	}
}
