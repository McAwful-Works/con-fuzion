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

import app.confuzion.backend.util.HttpUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.slf4j.Logger;
import org.springframework.boot.test.context.SpringBootTest;

import static org.mockito.Mockito.*;

/**
 * Unit tests for the {@link LoggerAspect} class.
 * 
 * @author mcawful
 */
@SpringBootTest
class LoggerAspectTest {
	@Mock
	private ProceedingJoinPoint pjp;

	@Mock
	private JoinPoint jp;

	@Mock
	private HttpServletRequest httpServletRequest;

	@InjectMocks
	private LoggerAspect loggerAspect;

	@Test
	void logAroundController() throws Throwable {
		var methodSignature = mock(MethodSignature.class);
		when(methodSignature.getMethod()).thenReturn(TestClass.class.getMethod("testMethod"));
	}

	@Test
	void logExceptionController() throws Throwable {
	}

	private static class TestClass {
		String testMethod() {
			return "Test";
		}
	}
}