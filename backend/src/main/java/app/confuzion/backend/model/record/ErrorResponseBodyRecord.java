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
package app.confuzion.backend.model.record;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import app.confuzion.backend.model.ResponseBodyObject;

/**
 * The response body object for errors. It carries only generic, per-status
 * text: never an exception message, root cause, class name or SQL.
 *
 * @param errorType     the HTTP status name, for example {@code CONFLICT}
 * @param errorMessage  the generic HTTP reason phrase, for example
 *                      {@code Conflict}
 * @param correlationId the ID that ties this response to the server-side log
 *                      entry, or {@code null} when nothing was logged
 * @author mcawful
 */
public record ErrorResponseBodyRecord(String errorType, String errorMessage, String correlationId)
		implements ResponseBodyObject {
	/**
	 * Creates the generic error body for the given status.
	 *
	 * @param status        the response {@link HttpStatusCode}
	 * @param correlationId the correlation ID, or {@code null}
	 * @return an {@link ErrorResponseBodyRecord}
	 */
	public static ErrorResponseBodyRecord of(HttpStatusCode status, String correlationId) {
		var httpStatus = HttpStatus.resolve(status.value());
		if (httpStatus == null)
			return new ErrorResponseBodyRecord("ERROR", "Error", correlationId);
		return new ErrorResponseBodyRecord(httpStatus.name(), httpStatus.getReasonPhrase(), correlationId);
	}
}
