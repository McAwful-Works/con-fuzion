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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs two app instances on real Tomcat ports against one Postgres and one
 * Valkey, the way ECS tasks run behind the ALB, and checks that they share
 * sessions and rate limits. Instance A is the test context; instance B is a
 * second {@link org.springframework.boot.SpringApplication} pointed at the same
 * containers.
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = TwoInstancesIntegrationTest.NO_REFILL)
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TwoInstancesIntegrationTest {
	/**
	 * Pushes the first refill an hour out, so a bucket's 10 tokens are exactly what
	 * both instances can spend between them.
	 */
	static final String NO_REFILL = "con-fuzion.rate-limiter.refill-rate-seconds=3600";

	/**
	 * The default bucket capacity.
	 */
	private static final int CAPACITY = 10;

	/**
	 * The {@link HttpClient}.
	 */
	private final HttpClient http = HttpClient.newHttpClient();

	/**
	 * Instance A's port.
	 */
	@LocalServerPort
	private int portA;

	/**
	 * The shared Postgres container.
	 */
	@Autowired
	private PostgreSQLContainer postgres;

	/**
	 * The shared Valkey container.
	 */
	@Autowired
	@Qualifier("valkeyContainer")
	private GenericContainer<?> valkey;

	/**
	 * Instance B.
	 */
	private ConfigurableApplicationContext instanceB;

	/**
	 * Instance B's port.
	 */
	private int portB;

	/**
	 * Starts instance B against the same containers.
	 */
	@BeforeAll
	void startInstanceB() {
		instanceB = new SpringApplicationBuilder(ApplicationContext.class).run("--server.port=0", "--" + NO_REFILL,
				"--spring.datasource.url=" + postgres.getJdbcUrl(),
				"--spring.datasource.username=" + postgres.getUsername(),
				"--spring.datasource.password=" + postgres.getPassword(),
				"--spring.data.redis.host=" + valkey.getHost(),
				"--spring.data.redis.port=" + valkey.getMappedPort(TestcontainersConfiguration.VALKEY_PORT),
				"--spring.data.redis.password=");
		portB = Integer.parseInt(instanceB.getEnvironment().getRequiredProperty("local.server.port"));
	}

	/**
	 * Stops instance B.
	 */
	@AfterAll
	void stopInstanceB() {
		if (instanceB != null)
			instanceB.close();
	}

	/**
	 * Tests that a session created on one instance is used by the other, and that
	 * the cookie is {@code HttpOnly; Secure; SameSite=Lax}.
	 */
	@Test
	void sessionsAreSharedBetweenInstances() throws Exception {
		var client = "X-Forwarded-For";
		var first = get(portA, "/api/test-probe/session", client, "198.51.100.60");
		var setCookie = first.headers().firstValue("Set-Cookie").orElseThrow();
		assertThat(setCookie).startsWith("SESSION=").contains("HttpOnly").contains("Secure").contains("SameSite=Lax");
		var cookie = setCookie.substring(0, setCookie.indexOf(';'));

		assertThat(first.body()).isEqualTo("1");
		assertThat(get(portB, "/api/test-probe/session", client, "198.51.100.60", "Cookie", cookie).body())
				.isEqualTo("2");
		assertThat(get(portA, "/api/test-probe/session", client, "198.51.100.60", "Cookie", cookie).body())
				.isEqualTo("3");
	}

	/**
	 * Tests that both instances spend one bucket: ten requests split across them
	 * use it up, so the next request on either is throttled. Each request also
	 * spoofs a different left-most {@code X-Forwarded-For} value, which must not
	 * give the caller a fresh bucket.
	 */
	@Test
	void rateLimitIsSharedBetweenInstancesAndIgnoresSpoofedForwardedFor() throws Exception {
		for (var i = 0; i < CAPACITY; i++) {
			var port = i % 2 == 0 ? portA : portB;
			assertThat(get(port, "/api/test-probe/ping", "X-Forwarded-For", "203.0.113." + i + ", 198.51.100.70")
					.statusCode()).isEqualTo(200);
		}

		assertThat(get(portA, "/api/test-probe/ping", "X-Forwarded-For", "203.0.113.99, 198.51.100.70").statusCode())
				.isEqualTo(429);
		assertThat(get(portB, "/api/test-probe/ping", "X-Forwarded-For", "198.51.100.70").statusCode()).isEqualTo(429);
	}

	/**
	 * Tests that health is public while metrics answers only loopback callers.
	 */
	@Test
	void healthIsPublicAndMetricsIsLoopbackOnly() throws Exception {
		assertThat(get(portA, "/api/actuator/health", "X-Forwarded-For", "203.0.113.50").statusCode()).isEqualTo(200);
		assertThat(get(portA, "/api/actuator/metrics", "X-Forwarded-For", "203.0.113.50").statusCode()).isEqualTo(404);
		assertThat(get(portA, "/api/actuator/%6detrics", "X-Forwarded-For", "203.0.113.50").statusCode())
				.isEqualTo(404);
		assertThat(get(portA, "/api/actuator/metrics").statusCode()).isEqualTo(200);
	}

	/**
	 * Sends a GET to an instance on 127.0.0.1.
	 *
	 * @param port    the instance's port
	 * @param path    the request path
	 * @param headers header name and value pairs
	 * @return the {@link HttpResponse}
	 */
	private HttpResponse<String> get(int port, String path, String... headers) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
		if (headers.length > 0)
			request.headers(headers);
		return http.send(request.build(), BodyHandlers.ofString());
	}
}
