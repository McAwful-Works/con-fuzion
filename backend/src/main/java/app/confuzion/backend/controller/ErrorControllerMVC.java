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
package app.confuzion.backend.controller;

import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.confuzion.backend.aspect.GlobalControllerExceptionHandler;
import app.confuzion.backend.model.record.ErrorResponseBodyRecord;
import app.confuzion.backend.model.record.ResponseRecord;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The {@link RestController} for the {@code /error} fallback endpoint, which
 * the container dispatches to for errors raised outside Spring MVC (in filters,
 * or by {@code response.sendError}). It answers with the same generic body and
 * correlation ID as {@link GlobalControllerExceptionHandler}.
 *
 * @author mcawful
 */
@RestController
public class ErrorControllerMVC implements ErrorController {
	/**
	 * The error handling controller method.
	 *
	 * @param request the {@link HttpServletRequest}
	 * @return the generic error response for the dispatched status, or a 500 when
	 *         the container gave none
	 */
	@RequestMapping(path = "/error")
	public ResponseEntity<ResponseRecord<ErrorResponseBodyRecord>> handleError(HttpServletRequest request) {
		var status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code
				? HttpStatusCode.valueOf(code)
				: HttpStatus.INTERNAL_SERVER_ERROR;
		var exception = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof Throwable t ? t : null;
		return GlobalControllerExceptionHandler.respond(status, exception);
	}
}
