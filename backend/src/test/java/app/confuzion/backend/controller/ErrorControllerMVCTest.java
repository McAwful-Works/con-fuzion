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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import app.confuzion.backend.aspect.GlobalControllerExceptionHandler;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Unit tests for the {@link ErrorControllerMVC} class.
 *
 * @author mcawful
 */
class ErrorControllerMVCTest {
	/**
	 * The {@link ErrorControllerMVC} to test.
	 */
	private final ErrorControllerMVC errorControllerMVC = new ErrorControllerMVC();

	/**
	 * Tests that a dispatched status, such as a filter's {@code sendError(404)}, is
	 * kept and gets a generic body with a correlation ID.
	 */
	@Test
	void testHandleErrorKeepsDispatchedStatus() {
		var request = new MockHttpServletRequest();
		request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);

		var response = errorControllerMVC.handleError(request, new MockHttpServletResponse());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().body().errorType()).isEqualTo("NOT_FOUND");
		assertThat(response.getBody().body().correlationId()).isNotBlank()
				.isEqualTo(response.getHeaders().getFirst(GlobalControllerExceptionHandler.CORRELATION_ID_HEADER));
	}

	/**
	 * Tests that a filter exception without a status becomes a generic 500 that
	 * doesn't carry the exception's message.
	 */
	@Test
	void testHandleErrorWithExceptionAndNoStatus() {
		var request = new MockHttpServletRequest();
		request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("valkey host 10.0.0.9"));

		var response = errorControllerMVC.handleError(request, new MockHttpServletResponse());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().body().errorMessage()).isEqualTo("Internal Server Error");
		assertThat(response.getBody().toString()).doesNotContain("valkey");
	}

	/**
	 * Tests the path that failed in #44: a filter fails after a static file had set
	 * its content type and cache headers, and the container forwards to
	 * {@code /error} with those still on the response (Tomcat resets only the
	 * buffer and length). The envelope must still be written, as JSON, and not be
	 * cacheable.
	 */
	@Test
	void testHandleErrorOverPresetStaticFileHeaders() throws Exception {
		var mockMvc = MockMvcBuilders.standaloneSetup(errorControllerMVC).addFilter((request, response, chain) -> {
			var httpResponse = (HttpServletResponse) response;
			httpResponse.setContentType("application/octet-stream");
			httpResponse.setHeader("Cache-Control", "max-age=31536000, public, immutable");
			httpResponse.getOutputStream().write(new byte[] { 1, 2, 3 });
			chain.doFilter(request, response);
		}).build();

		mockMvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
				.requestAttr(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("valkey host 10.0.0.9")))
				.andExpect(status().isInternalServerError())
				.andExpect(content().contentTypeCompatibleWith("application/json"))
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.responseType").value("ERROR"))
				.andExpect(jsonPath("$.body.errorType").value("INTERNAL_SERVER_ERROR"))
				.andExpect(jsonPath("$.body.correlationId").isNotEmpty());
	}
}
