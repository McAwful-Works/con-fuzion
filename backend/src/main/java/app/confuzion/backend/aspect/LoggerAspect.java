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

import java.util.Arrays;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import app.confuzion.backend.util.HttpUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Generated;
import tools.jackson.databind.ObjectMapper;

/**
 * Logger aspect.
 *
 * @author mcawful
 */
@Aspect
@Component
public class LoggerAspect {
	/**
	 * Predicate wrapper method.
	 *
	 * @param <T>       the generic class type
	 * @param predicate the {@link Predicate} to be wrapped
	 * @return a wrapped {@link Predicate}
	 */
	private static <T> Predicate<T> predicate(Predicate<T> predicate) {
		return predicate;
	}

	/**
	 * The controller pointcut.
	 */
	@Generated
	@Pointcut("within(app.confuzion.backend.controller.*)")
	public void controllerPointcut() {
		// This method is intentionally left empty
	}

	/**
	 * Around advice logger for controller methods.
	 *
	 * @param pjp the {@link ProceedingJoinPoint}
	 * @return the result of proceeding
	 * @throws Throwable When the invoked proceed throws anything
	 */
	@Around("controllerPointcut()")
	public Object logAroundController(ProceedingJoinPoint pjp) throws Throwable {
		final Logger logger = LoggerFactory.getLogger(pjp.getTarget().getClass());

		var methodName = getMethodName(pjp);
		var methodArgs = getArgs(pjp);
		var remoteAddr = getRemoteAddr(pjp);
		var headers = getHeaders(pjp);

		logger.info("Method '{}{}' called from the following IP address '{}' with the following headers - {}",
				methodName, methodArgs, remoteAddr, headers);
		var objectMapper = new ObjectMapper();
		var proceed = pjp.proceed();
		var json = objectMapper.writeValueAsString(proceed);
		logger.info("Method '{}{}' returned the following response: {}", methodName, methodArgs, json);
		return proceed;
	}

	/**
	 * After throwing advice logger for controller methods.
	 *
	 * @param jp the {@link JoinPoint}
	 * @param e  the {@link Exception}
	 */
	@AfterThrowing(pointcut = "controllerPointcut()", throwing = "e")
	public void logExceptionController(JoinPoint jp, Exception e) {
		final Logger logger = LoggerFactory.getLogger(jp.getTarget().getClass());
		var methodName = getMethodName(jp);
		var args = getArgs(jp);
		logger.error("Exception during method '{}{}' with the following exception - {}: {}", methodName, args,
				e.getClass().getSimpleName(), e.getMessage());
	}

	/**
	 * Gets and returns the method name of a {@link JoinPoint} as a string.
	 *
	 * @param jp the {@link JoinPoint}
	 * @return the method name as a string
	 */
	private String getMethodName(JoinPoint jp) {
		return ((MethodSignature) jp.getSignature()).getMethod().getName();
	}

	/**
	 * Gets and returns the parameter arguments of a {@link JoinPoint} as a string.
	 *
	 * @param jp the {@link JoinPoint}
	 * @return the parameter arguments as a string
	 */
	private String getArgs(JoinPoint jp) {
		return Arrays.stream(jp.getArgs()).filter(predicate(HttpServletRequest.class::isInstance).negate())
				.map(String::valueOf).collect(Collectors.joining(",", "(", ")"));
	}

	/**
	 * Gets and returns the request headers of a {@link JoinPoint} as a string if an
	 * {@link HttpServletRequest} is given in the method parameters.
	 *
	 * @param jp The {@link JoinPoint}
	 * @return the headers as a string
	 */
	private String getHeaders(JoinPoint jp) {
		var headerOptional = Arrays.stream(jp.getArgs()).filter(HttpServletRequest.class::isInstance).findFirst();

		if (headerOptional.isPresent())
			return HttpUtil.getRequestHeaders((HttpServletRequest) headerOptional.get());

		return "Headers not provided in this method's parameters";
	}

	/**
	 * Gets and returns the remote IP address of a {@link JoinPoint} as a string if
	 * an {@link HttpServletRequest} is given in the method parameters.
	 *
	 * @param jp The {@link JoinPoint}
	 * @return the remote IP address string
	 */
	private String getRemoteAddr(JoinPoint jp) {
		var headerOptional = Arrays.stream(jp.getArgs()).filter(HttpServletRequest.class::isInstance).findFirst();

		if (headerOptional.isPresent())
			return HttpUtil.getRemoteAddr((HttpServletRequest) headerOptional.get());

		return "Remote IP address not provided in this method's parameters";
	}
}
