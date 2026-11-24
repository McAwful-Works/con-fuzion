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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import app.confuzion.backend.TestcontainersConfiguration;
import app.confuzion.backend.controller.IntegrationProbeController;

/**
 * Integration tests for the {@link GlobalControllerExceptionHandler}: real
 * failures through the full MVC stack produce generic bodies only.
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class GlobalControllerExceptionHandlerIntegrationTest {
	/**
	 * The {@link WebApplicationContext}.
	 */
	@Autowired
	private WebApplicationContext webApplicationContext;

	/**
	 * The {@link MockMvc}.
	 */
	private MockMvc mockMvc;

	/**
	 * Builds the {@link MockMvc}.
	 */
	@BeforeEach
	void setup() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
	}

	/**
	 * Tests that a forced Postgres unique-constraint violation returns a generic
	 * 409 with no SQL, constraint, table or Hibernate text.
	 */
	@Test
	void constraintViolationReturnsGenericConflict() throws Exception {
		var result = perform("/api/test-probe/conflict", status().isConflict());

		assertGenericBody(result, "CONFLICT", "Conflict");
		assertThat(result.getResponse().getContentAsString()).doesNotContainIgnoringCase("probe_secret")
				.doesNotContainIgnoringCase("duplicate").doesNotContainIgnoringCase("insert")
				.doesNotContainIgnoringCase("sql").doesNotContainIgnoringCase("hibernate")
				.doesNotContainIgnoringCase("exception");
	}

	/**
	 * Tests that an unexpected exception returns a generic 500 without its message
	 * or type.
	 */
	@Test
	void unexpectedExceptionReturnsGenericServerError() throws Exception {
		var result = perform("/api/test-probe/boom", status().isInternalServerError());

		assertGenericBody(result, "INTERNAL_SERVER_ERROR", "Internal Server Error");
		assertThat(result.getResponse().getContentAsString()).doesNotContain(IntegrationProbeController.INTERNAL_DETAIL)
				.doesNotContain("IllegalState");
	}

	/**
	 * Tests that an unmapped path keeps its 404 rather than falling into the 500
	 * catch-all.
	 */
	@Test
	void unmappedPathReturnsGenericNotFound() throws Exception {
		var result = perform("/api/no-such-endpoint", status().isNotFound());

		assertGenericBody(result, "NOT_FOUND", "Not Found");
	}

	/**
	 * Performs a GET from a test IP and checks the status.
	 *
	 * @param path   the request path
	 * @param status the expected status matcher
	 * @return the {@link MvcResult}
	 */
	private MvcResult perform(String path, ResultMatcher status) throws Exception {
		return mockMvc.perform(get(path).with(request -> {
			request.setRemoteAddr("198.51.100.10");
			return request;
		})).andExpect(status).andReturn();
	}

	/**
	 * Asserts the body is the generic one and its correlation ID matches the
	 * header.
	 *
	 * @param result       the {@link MvcResult}
	 * @param errorType    the expected error type
	 * @param errorMessage the expected error message
	 */
	private void assertGenericBody(MvcResult result, String errorType, String errorMessage) throws Exception {
		var correlationId = result.getResponse().getHeader(GlobalControllerExceptionHandler.CORRELATION_ID_HEADER);
		assertThat(correlationId).isNotBlank();
		jsonPath("$.errorType").value(errorType).match(result);
		jsonPath("$.errorMessage").value(errorMessage).match(result);
		jsonPath("$.correlationId").value(correlationId).match(result);
		header().string("Content-Type", "application/json").match(result);
	}
}
