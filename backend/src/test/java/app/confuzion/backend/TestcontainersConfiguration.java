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

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The Testcontainers base for integration tests: Postgres 16 and Valkey 8, the
 * same images as {@code infra/docker-compose.yml}. Import it from a test tagged
 * {@code @Tag("integration")}; Spring's context cache shares the containers
 * between test classes with the same configuration.
 *
 * @author mcawful
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {
	/**
	 * The Postgres image.
	 */
	public static final String POSTGRES_IMAGE = "postgres:16-alpine";

	/**
	 * The Valkey image.
	 */
	public static final String VALKEY_IMAGE = "valkey/valkey:8-alpine";

	/**
	 * The Valkey port inside the container.
	 */
	public static final int VALKEY_PORT = 6379;

	/**
	 * Creates the Postgres container.
	 *
	 * @return the {@link PostgreSQLContainer}
	 */
	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE));
	}

	/**
	 * Creates the Valkey container, wired in as a Redis service connection.
	 *
	 * @return the Valkey {@link GenericContainer}
	 */
	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> valkeyContainer() {
		return new GenericContainer<>(DockerImageName.parse(VALKEY_IMAGE)).withExposedPorts(VALKEY_PORT);
	}
}
