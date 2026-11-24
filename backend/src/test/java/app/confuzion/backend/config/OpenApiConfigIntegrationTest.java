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
import app.confuzion.backend.exception.LimitExceededException;

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

		assertThat(paths).containsOnlyKeys("/api/me", "/api/me/guilds", "/api/me/guilds/{guildId}", "/api/auth/logout",
				"/api/auth/logout-everywhere", "/api/friends", "/api/friends/{userId}", "/api/friends/invites",
				"/api/friends/invites/{token}", "/api/friends/invites/{token}/accept", "/api/people", "/api/circles",
				"/api/circles/{circleId}", "/api/circles/{circleId}/members/{userId}", "/api/blocks",
				"/api/blocks/{userId}", "/api/events", "/api/events/{eventId}");
		assertThat(paths.get("/api/me")).containsOnlyKeys("get", "patch");
		assertThat(paths.get("/api/me/guilds")).containsOnlyKeys("get", "put");
		assertThat(paths.get("/api/me/guilds/{guildId}")).containsOnlyKeys("put");
		assertThat(paths.get("/api/auth/logout")).containsOnlyKeys("post");
		assertThat(paths.get("/api/auth/logout-everywhere")).containsOnlyKeys("post");
		assertThat(paths.get("/api/friends")).containsOnlyKeys("get");
		assertThat(paths.get("/api/friends/{userId}")).containsOnlyKeys("delete");
		assertThat(paths.get("/api/friends/invites")).containsOnlyKeys("post");
		assertThat(paths.get("/api/friends/invites/{token}")).containsOnlyKeys("get");
		assertThat(paths.get("/api/friends/invites/{token}/accept")).containsOnlyKeys("post");
		assertThat(paths.get("/api/people")).containsOnlyKeys("get");
		assertThat(paths.get("/api/circles")).containsOnlyKeys("get", "post");
		assertThat(paths.get("/api/circles/{circleId}")).containsOnlyKeys("get", "patch", "delete");
		assertThat(paths.get("/api/circles/{circleId}/members/{userId}")).containsOnlyKeys("put", "delete");
		assertThat(paths.get("/api/blocks")).containsOnlyKeys("get");
		assertThat(paths.get("/api/blocks/{userId}")).containsOnlyKeys("put", "delete");
		assertThat(paths.get("/api/events")).containsOnlyKeys("get", "post");
		assertThat(paths.get("/api/events/{eventId}")).containsOnlyKeys("get", "patch", "delete");
	}

	/**
	 * Tests the events' success bodies and their documented errors.
	 */
	@Test
	void eventSuccessBodies() {
		assertThat(successRef("/api/events", "post", "201"))
				.isEqualTo("#/components/schemas/ResponseRecordEventResponse");
		assertThat(successRef("/api/events", "get")).isEqualTo("#/components/schemas/ResponseRecordEventPageResponse");
		assertThat(successRef("/api/events/{eventId}", "get"))
				.isEqualTo("#/components/schemas/ResponseRecordEventResponse");
		assertThat(successRef("/api/events/{eventId}", "patch"))
				.isEqualTo("#/components/schemas/ResponseRecordEventResponse");
		assertThat(responses("/api/events/{eventId}", "delete")).containsKey("204").doesNotContainKey("200");
		assertThat(responses("/api/events", "post")).containsKeys("201", "400", "403", "404", "429")
				.doesNotContainKey("200");
		assertThat(responses("/api/events/{eventId}", "patch")).containsKeys("200", "400", "403", "404", "409");
		assertThat(responses("/api/events", "get")).containsKey("400").doesNotContainKey("403");
		assertThat(document.<String>read("$.paths['/api/events'].post.responses['429'].description"))
				.contains("EVENT_LIMIT");
	}

	/**
	 * Tests the event schemas: {@code isHost} under that name, a nullable location
	 * and audience, and typed audience entries with no free-text reference.
	 */
	@Test
	void eventSchemas() {
		Map<String, Map<String, Object>> properties = document.read("$.components.schemas.EventResponse.properties");
		assertThat(properties).containsOnlyKeys("id", "host", "title", "description", "location", "locationHidden",
				"locationVisibility", "tag", "startsAt", "endsAt", "timeZone", "signupOpensAt", "capacity", "status",
				"isHost", "audience");
		assertThat((List<String>) properties.get("location").get("type")).contains("null");
		assertThat((List<String>) properties.get("audience").get("type")).contains("null");
		assertThat(document.<Map<String, Object>>read("$.components.schemas.AudienceEntryRequest.properties"))
				.containsOnlyKeys("type", "guildId", "circleId", "userId");
		assertThat(document.<Map<String, Object>>read("$.components.schemas.AudienceEntryResponse.properties"))
				.containsOnlyKeys("type", "guildId", "circleId", "userId", "label");
		assertThat(document.<Integer>read("$.components.schemas.EventRequest.properties.audience.maxItems"))
				.isEqualTo(50);
	}

	/**
	 * Tests the social graph's success bodies: typed envelopes, 201 for creates and
	 * a bodiless 204 for removals.
	 */
	@Test
	void socialSuccessBodies() {
		assertThat(successRef("/api/friends", "get"))
				.isEqualTo("#/components/schemas/ResponseRecordPersonListResponse");
		assertThat(successRef("/api/friends/invites", "post", "201"))
				.isEqualTo("#/components/schemas/ResponseRecordInviteResponse");
		assertThat(successRef("/api/friends/invites/{token}", "get"))
				.isEqualTo("#/components/schemas/ResponseRecordInvitePreviewResponse");
		assertThat(successRef("/api/friends/invites/{token}/accept", "post"))
				.isEqualTo("#/components/schemas/ResponseRecordPersonResponse");
		assertThat(successRef("/api/people", "get")).isEqualTo("#/components/schemas/ResponseRecordPersonListResponse");
		assertThat(successRef("/api/blocks", "get")).isEqualTo("#/components/schemas/ResponseRecordPersonListResponse");
		assertThat(successRef("/api/circles", "get"))
				.isEqualTo("#/components/schemas/ResponseRecordCircleListResponse");
		assertThat(successRef("/api/circles", "post", "201"))
				.isEqualTo("#/components/schemas/ResponseRecordCircleResponse");
		assertThat(successRef("/api/circles/{circleId}", "get"))
				.isEqualTo("#/components/schemas/ResponseRecordCircleResponse");
		assertThat(successRef("/api/circles/{circleId}", "patch"))
				.isEqualTo("#/components/schemas/ResponseRecordCircleResponse");
		for (var removal : List.of(List.of("/api/friends/{userId}", "delete"),
				List.of("/api/circles/{circleId}", "delete"),
				List.of("/api/circles/{circleId}/members/{userId}", "put"),
				List.of("/api/circles/{circleId}/members/{userId}", "delete"), List.of("/api/blocks/{userId}", "put"),
				List.of("/api/blocks/{userId}", "delete")))
			assertThat(responses(removal.get(0), removal.get(1))).containsKey("204").doesNotContainKey("200");
		assertThat(responses("/api/friends/invites", "post")).containsKeys("201", "429").doesNotContainKey("200");
		assertThat(responses("/api/circles", "post")).containsKeys("201", "409").doesNotContainKey("200");
		assertThat(responses("/api/circles/{circleId}", "patch")).containsKeys("200", "409");
		assertThat(responses("/api/friends/invites/{token}", "get")).containsKeys("200", "404");
		assertThat(responses("/api/friends/invites/{token}/accept", "post")).containsKeys("200", "403", "404");
		assertThat(responses("/api/people", "get")).containsKey("400").doesNotContainKey("403");
		assertThat(document.<String>read("$.paths['/api/friends/invites'].post.responses['429'].description"))
				.contains(LimitExceededException.Limit.INVITE_LIMIT.name());
	}

	/**
	 * Tests that people are identified by our user ID only: no Discord ID, username
	 * or email in a public profile, and no ID at all for an inviter.
	 */
	@Test
	void personSchemasCarryNoDiscordId() {
		assertThat(document.<Map<String, Object>>read("$.components.schemas.PersonResponse.properties"))
				.containsOnlyKeys("id", "displayName", "avatarUrl");
		assertThat(document.<Map<String, Object>>read("$.components.schemas.InvitePreviewInviter.properties"))
				.containsOnlyKeys("displayName", "avatarUrl");
		assertThat(document.<Map<String, Object>>read("$.components.schemas.CircleResponse.properties"))
				.containsOnlyKeys("id", "name", "members");
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
		assertThat(responses("/api/auth/logout", "post")).containsOnlyKeys("204", "403", "500", "default");
	}

	/**
	 * Tests that every operation documents its 4xx/5xx responses with the error
	 * envelope.
	 */
	@Test
	void everyOperationDocumentsTheErrorEnvelope() {
		List<Map<String, Map<String, Object>>> responses = document.read("$.paths.*.*.responses");

		assertThat(responses).hasSize(28).allSatisfy(byStatus -> {
			assertThat(byStatus).containsKeys("500", "default");
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
		document.<Map<String, Map<String, Map<String, Object>>>>read("$.paths").forEach((path, item) -> {
			if (!path.equals("/api/auth/logout"))
				item.values().forEach(operation -> assertThat((Map<String, Object>) operation.get("responses"))
						.containsKeys("401", "429"));
		});
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
		return successRef(path, method, "200");
	}

	/**
	 * Returns the schema reference of an operation's success response.
	 *
	 * @param path   the path
	 * @param method the HTTP method, in lower case
	 * @param status the success status
	 * @return the {@code $ref}
	 */
	private String successRef(String path, String method, String status) {
		return document.read("$.paths['" + path + "']." + method + ".responses['" + status
				+ "'].content['application/json'].schema.$ref");
	}
}
