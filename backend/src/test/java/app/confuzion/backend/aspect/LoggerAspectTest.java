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
import static org.mockito.Mockito.when;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Unit tests for the {@link LoggerAspect} class: one metadata line per call,
 * with nothing sensitive in it.
 *
 * @author mcawful
 */
@ExtendWith(MockitoExtension.class)
class LoggerAspectTest {
	/**
	 * A value that must never be logged.
	 */
	private static final String SECRET = "invite-token-abc123";

	/**
	 * The mock {@link ProceedingJoinPoint}.
	 */
	@Mock
	private ProceedingJoinPoint proceedingJoinPoint;

	/**
	 * The current request.
	 */
	private MockHttpServletRequest request;

	/**
	 * The current response.
	 */
	private MockHttpServletResponse response;

	/**
	 * Captures this class's log events; the aspect logs as the target class.
	 */
	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	/**
	 * The {@link LoggerAspect} to test.
	 */
	private final LoggerAspect loggerAspect = new LoggerAspect();

	/**
	 * Binds a request carrying sensitive data and attaches the appender.
	 */
	@BeforeEach
	void setup() {
		request = new MockHttpServletRequest("POST", "/api/friends/invites/" + SECRET + "/accept");
		request.setQueryString("code=" + SECRET);
		request.addHeader("Cookie", "SESSION=" + SECRET);
		request.addHeader("Authorization", "Bearer " + SECRET);
		request.setContent(SECRET.getBytes());
		request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/friends/invites/{token}/accept");
		response = new MockHttpServletResponse();
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
		when(proceedingJoinPoint.getTarget()).thenReturn(this);
		appender.start();
		((Logger) LoggerFactory.getLogger(LoggerAspectTest.class)).addAppender(appender);
	}

	/**
	 * Detaches the appender and unbinds the request.
	 */
	@AfterEach
	void tearDown() {
		((Logger) LoggerFactory.getLogger(LoggerAspectTest.class)).detachAppender(appender);
		RequestContextHolder.resetRequestAttributes();
	}

	/**
	 * Tests that a returned {@link ResponseEntity}'s status, the route pattern and
	 * the user ID are logged, and the arguments and response body aren't.
	 */
	@Test
	void testLogAroundControllerWithResponseEntity() throws Throwable {
		request.setUserPrincipal(() -> "1234567890");
		when(proceedingJoinPoint.proceed()).thenReturn(ResponseEntity.status(201).body(SECRET));

		loggerAspect.logAroundController(proceedingJoinPoint);

		assertThat(singleLine()).startsWith("POST /api/friends/invites/{token}/accept -> 201 in ")
				.endsWith("(user 1234567890)");
	}

	/**
	 * Tests that a plain return value logs the response's status and an anonymous
	 * user.
	 */
	@Test
	void testLogAroundControllerWithPlainResult() throws Throwable {
		response.setStatus(202);
		when(proceedingJoinPoint.proceed()).thenReturn(SECRET);

		assertThat(loggerAspect.logAroundController(proceedingJoinPoint)).isEqualTo(SECRET);

		assertThat(singleLine()).contains("-> 202 in ").endsWith("(user anonymous)");
	}

	/**
	 * Tests that an exception is rethrown and logged by type only, without its
	 * message.
	 */
	@Test
	void testLogAroundControllerWhenControllerThrows() throws Throwable {
		when(proceedingJoinPoint.proceed()).thenThrow(new IllegalStateException(SECRET));

		assertThatThrownBy(() -> loggerAspect.logAroundController(proceedingJoinPoint))
				.isInstanceOf(IllegalStateException.class);

		assertThat(singleLine()).contains("-> threw IllegalStateException in ");
	}

	/**
	 * Tests that a request without a matched route logs {@code unknown} rather than
	 * the raw URI.
	 */
	@Test
	void testLogAroundControllerWithoutRoutePattern() throws Throwable {
		request.removeAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);

		loggerAspect.logAroundController(proceedingJoinPoint);

		assertThat(singleLine()).startsWith("POST unknown -> 200 in ");
	}

	/**
	 * Asserts exactly one line was logged, that it holds no sensitive value, and
	 * returns it.
	 *
	 * @return the formatted log line
	 */
	private String singleLine() {
		assertThat(appender.list).hasSize(1);
		var line = appender.list.getFirst().getFormattedMessage();
		assertThat(line).doesNotContain(SECRET);
		return line;
	}
}
