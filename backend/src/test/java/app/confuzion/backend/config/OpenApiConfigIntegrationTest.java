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
package app.confuzion.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import app.confuzion.backend.TestcontainersConfiguration;

/**
 * Exports the OpenAPI document to {@value #DOCUMENT} and checks the contract in
 * it. Running the integration tests regenerates the file, and CI fails when the
 * regenerated file differs from the committed one (the drift check in
 * {@code .github/workflows/backend.yml}). To update it after a contract change:
 * {@code ./gradlew test --tests '*OpenApiConfigIntegrationTest'}.
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenApiConfigIntegrationTest {
	/**
	 * The committed document, relative to the backend project directory that Gradle
	 * runs tests from.
	 */
	static final String DOCUMENT = "../docs/api/openapi.json";

	/**
	 * The error envelope's schema reference.
	 */
	private static final String ERROR_ENVELOPE_REF = "#/components/schemas/ResponseRecordErrorResponseBodyRecord";

	/**
	 * The {@link WebApplicationContext}.
	 */
	@Autowired
	private WebApplicationContext webApplicationContext;

	/**
	 * The generated document.
	 */
	private DocumentContext document;

	/**
	 * Generates the document and writes it to {@value #DOCUMENT} with LF line
	 * endings, so it is byte-identical on every OS.
	 */
	@BeforeAll
	void export() throws Exception {
		var json = MockMvcBuilders.webAppContextSetup(webApplicationContext).build().perform(get("/v3/api-docs"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)
				.replace("\r\n", "\n").strip() + "\n";
		var path = Path.of(DOCUMENT);
		Files.createDirectories(path.getParent());
		Files.writeString(path, json, StandardCharsets.UTF_8);
		document = JsonPath.parse(json);
	}

	/**
	 * Tests that the document has exactly the contract's operations.
	 */
	@Test
	void documentHasTheContractOperations() {
		Map<String, Map<String, Object>> paths = document.read("$.paths");

		assertThat(paths).containsOnlyKeys("/api/me", "/api/me/guilds", "/api/me/guilds/{guildId}",
				"/api/auth/logout-everywhere");
		assertThat(paths.get("/api/me")).containsOnlyKeys("get", "patch");
		assertThat(paths.get("/api/me/guilds")).containsOnlyKeys("get", "put");
		assertThat(paths.get("/api/me/guilds/{guildId}")).containsOnlyKeys("put");
		assertThat(paths.get("/api/auth/logout-everywhere")).containsOnlyKeys("post");
	}

	/**
	 * Tests that success bodies are the envelope around the right body type.
	 */
	@Test
	void successBodiesAreEnveloped() {
		assertThat(successRef("/api/me", "get")).isEqualTo("#/components/schemas/ResponseRecordMeResponse");
		assertThat(successRef("/api/me", "patch")).isEqualTo("#/components/schemas/ResponseRecordMeResponse");
		assertThat(successRef("/api/me/guilds", "get"))
				.isEqualTo("#/components/schemas/ResponseRecordGuildListResponse");
		assertThat(successRef("/api/me/guilds", "put"))
				.isEqualTo("#/components/schemas/ResponseRecordGuildListResponse");
		assertThat(successRef("/api/me/guilds/{guildId}", "put"))
				.isEqualTo("#/components/schemas/ResponseRecordGuildResponse");
		assertThat(responses("/api/auth/logout-everywhere", "post")).containsKey("204");
	}

	/**
	 * Tests that every operation documents its 4xx/5xx responses with the error
	 * envelope.
	 */
	@Test
	void everyOperationDocumentsTheErrorEnvelope() {
		List<Map<String, Map<String, Object>>> responses = document.read("$.paths.*.*.responses");

		assertThat(responses).hasSize(6).allSatisfy(byStatus -> {
			assertThat(byStatus).containsKeys("401", "429", "500", "default");
			byStatus.forEach((code, response) -> {
				if (code.startsWith("4") || code.startsWith("5") || code.equals("default"))
					assertThat(JsonPath.<String>read(response, "$.content['application/json'].schema.$ref"))
							.isEqualTo(ERROR_ENVELOPE_REF);
			});
		});
		assertThat(responses("/api/me/guilds/{guildId}", "put")).containsKeys("400", "404");
		assertThat(responses("/api/me", "patch")).containsKey("400");
		assertThat(responses("/api/me", "patch")).containsKey("403");
		assertThat(responses("/api/me/guilds/{guildId}", "put")).containsKey("403");
		assertThat(responses("/api/auth/logout-everywhere", "post")).containsKey("403");
		assertThat(responses("/api/me", "get")).doesNotContainKey("403");
		assertThat(responses("/api/me/guilds", "get")).doesNotContainKey("403");
		assertThat(responses("/api/me/guilds", "put")).containsKeys("400", "403", "404");
	}

	/**
	 * Tests the error envelope and body schemas.
	 */
	@Test
	void errorEnvelopeSchema() {
		assertThat((List<String>) document.read("$.components.schemas.ResponseRecordErrorResponseBodyRecord.required"))
				.containsExactlyInAnyOrder("responseType", "body");
		assertThat((String) document
				.read("$.components.schemas.ResponseRecordErrorResponseBodyRecord.properties.body.$ref"))
				.isEqualTo("#/components/schemas/ErrorResponseBodyRecord");
		assertThat((List<String>) document.read("$.components.schemas.ErrorResponseBodyRecord.required"))
				.containsExactlyInAnyOrder("errorType", "errorMessage", "correlationId");
	}

	/**
	 * Tests that the user profile never carries an email.
	 */
	@Test
	void meResponseHasNoEmail() {
		Map<String, Object> properties = document.read("$.components.schemas.MeResponse.properties");

		assertThat(properties).containsOnlyKeys("id", "username", "globalName", "avatarUrl", "timeZone", "pushEnabled",
				"roles", "mfaEnabled");
	}

	/**
	 * Returns an operation's responses by status.
	 *
	 * @param path   the path
	 * @param method the HTTP method, in lower case
	 * @return the responses
	 */
	private Map<String, Object> responses(String path, String method) {
		return document.read("$.paths['" + path + "']." + method + ".responses");
	}

	/**
	 * Returns the schema reference of an operation's 200 response.
	 *
	 * @param path   the path
	 * @param method the HTTP method, in lower case
	 * @return the {@code $ref}
	 */
	private String successRef(String path, String method) {
		return document.read(
				"$.paths['" + path + "']." + method + ".responses['200'].content['application/json'].schema.$ref");
	}
}
