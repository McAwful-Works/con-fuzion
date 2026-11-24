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

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import app.confuzion.backend.aspect.GlobalControllerExceptionHandler;

/**
 * Shared helpers for the contract-stub controller tests: a standalone
 * {@link MockMvc} with the real exception handler and Bean Validation, and an
 * assertion for the generic error envelope (D-010).
 *
 * @author mcawful
 */
final class ErrorEnvelopeAssert {
	/**
	 * Not instantiable.
	 */
	private ErrorEnvelopeAssert() {
	}

	/**
	 * Builds a standalone {@link MockMvc} for a controller.
	 *
	 * @param controller the controller
	 * @return the {@link MockMvc}
	 */
	static MockMvc mockMvc(Object controller) {
		return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalControllerExceptionHandler())
				.build();
	}

	/**
	 * Adds a JSON body to a request.
	 *
	 * @param request the request builder
	 * @param body    the JSON body
	 * @return the request builder
	 */
	static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	/**
	 * Performs a request and asserts the generic error envelope for a status.
	 *
	 * @param mockMvc the {@link MockMvc}
	 * @param request the request
	 * @param status  the expected {@link HttpStatus}
	 */
	static void assertError(MockMvc mockMvc, RequestBuilder request, HttpStatus status) throws Exception {
		mockMvc.perform(request).andExpect(status().is(status.value()))
				.andExpect(header().exists(GlobalControllerExceptionHandler.CORRELATION_ID_HEADER))
				.andExpect(jsonPath("$.responseType").value("ERROR"))
				.andExpect(jsonPath("$.body.errorType").value(status.name()))
				.andExpect(jsonPath("$.body.errorMessage").value(status.getReasonPhrase()))
				.andExpect(jsonPath("$.body.correlationId").isNotEmpty());
	}
}
