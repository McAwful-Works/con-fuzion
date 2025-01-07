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

import static org.mockito.Mockito.*;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.test.context.SpringBootTest;

import app.confuzion.backend.util.HttpUtil;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Unit tests for the {@link LoggerAspect} class.
 * 
 * @author mcawful
 */
@SpringBootTest
class LoggerAspectTest {
	@Mock
	private ProceedingJoinPoint proceedingJoinPoint;

	@Mock
	private JoinPoint joinPoint;

	@Mock
	private MethodSignature methodSignature;

	@Mock
	private HttpServletRequest httpServletRequest;

	@InjectMocks
	private LoggerAspect loggerAspect;

	@BeforeEach
	void setup() {
		MockitoAnnotations.openMocks(this);
	}

	@Test
	void testLogAroundController() throws Throwable {
		when(proceedingJoinPoint.getTarget()).thenReturn(this);
		when(proceedingJoinPoint.getSignature()).thenReturn(methodSignature);
		when(methodSignature.getMethod()).thenReturn(this.getClass().getDeclaredMethod("testLogAroundController"));
		when(proceedingJoinPoint.getArgs()).thenReturn(new Object[] { httpServletRequest, "testArg" });
		when(httpServletRequest.getHeader("host")).thenReturn("localhost");
		when(HttpUtil.getRequestHeaders(httpServletRequest)).thenReturn("[host:\"localhost\"]");
		when(HttpUtil.getRemoteAddr(httpServletRequest)).thenReturn("192.168.1.1");

		loggerAspect.logAroundController(proceedingJoinPoint);

		verify(proceedingJoinPoint, times(1)).proceed();
	}

	@Test
	void testLogExceptionController() throws NoSuchMethodException {
		when(joinPoint.getTarget()).thenReturn(this);
		when(joinPoint.getSignature()).thenReturn(methodSignature);
		when(methodSignature.getMethod()).thenReturn(this.getClass().getDeclaredMethod("testLogExceptionController"));
		when(joinPoint.getArgs()).thenReturn(new Object[] { httpServletRequest });

		var exception = new IllegalArgumentException("Test exception");

		loggerAspect.logExceptionController(joinPoint, exception);

		verify(joinPoint, times(1)).getSignature();
	}

	@Test
	void testLogAroundControllerWithoutHttpServletRequest() throws Throwable {
		when(proceedingJoinPoint.getTarget()).thenReturn(this);
		when(proceedingJoinPoint.getSignature()).thenReturn(methodSignature);
		when(methodSignature.getMethod()).thenReturn(this.getClass().getDeclaredMethod("testLogAroundController"));
		when(proceedingJoinPoint.getArgs()).thenReturn(new Object[] { "testArg" });

		loggerAspect.logAroundController(proceedingJoinPoint);

		verify(proceedingJoinPoint, times(1)).proceed();
	}
}
