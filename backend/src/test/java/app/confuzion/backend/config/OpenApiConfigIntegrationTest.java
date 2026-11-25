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
import app.confuzion.backend.exception.ConflictException.Conflict;
import app.confuzion.backend.model.record.PushSubscriptionRequest;
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
	 * Every {@code myStatus} value, in order.
	 */
	private static final List<String> MY_STATUSES = List.of("NONE", "CONFIRMED", "WAITLISTED", "OFFERED", "REMOVED");

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
				"/api/blocks/{userId}", "/api/events", "/api/events/{eventId}", "/api/events/{eventId}/rsvp",
				"/api/events/{eventId}/attendees", "/api/events/{eventId}/rsvps/{userId}",
				"/api/events/{eventId}/spots", "/api/events/{eventId}/rsvp/accept", "/api/notifications",
				"/api/notifications/read", "/api/push/key", "/api/push/subscriptions", "/api/reports",
				"/api/admin/reports", "/api/admin/reports/{reportId}", "/api/admin/reports/{reportId}/resolve",
				"/api/admin/events/{eventId}/hide", "/api/admin/events/{eventId}/unhide",
				"/api/admin/events/{eventId}/cancel", "/api/admin/users", "/api/admin/users/{userId}/suspend",
				"/api/admin/users/{userId}/unsuspend", "/api/admin/bans", "/api/admin/bans/{discordId}",
				"/api/admin/users/{userId}/roles", "/api/admin/audit");
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
		assertThat(paths.get("/api/events/{eventId}/rsvp")).containsOnlyKeys("post", "delete");
		assertThat(paths.get("/api/events/{eventId}/attendees")).containsOnlyKeys("get");
		assertThat(paths.get("/api/events/{eventId}/rsvps/{userId}")).containsOnlyKeys("delete");
		assertThat(paths.get("/api/events/{eventId}/spots")).containsOnlyKeys("get");
		assertThat(paths.get("/api/events/{eventId}/rsvp/accept")).containsOnlyKeys("post");
		assertThat(paths.get("/api/notifications")).containsOnlyKeys("get");
		assertThat(paths.get("/api/notifications/read")).containsOnlyKeys("post");
		assertThat(paths.get("/api/push/key")).containsOnlyKeys("get");
		assertThat(paths.get("/api/push/subscriptions")).containsOnlyKeys("post", "delete");
		assertThat(paths.get("/api/reports")).containsOnlyKeys("post");
		assertThat(paths.get("/api/admin/reports")).containsOnlyKeys("get");
		assertThat(paths.get("/api/admin/reports/{reportId}")).containsOnlyKeys("get");
		assertThat(paths.get("/api/admin/reports/{reportId}/resolve")).containsOnlyKeys("post");
		for (var action : List.of("hide", "unhide", "cancel"))
			assertThat(paths.get("/api/admin/events/{eventId}/" + action)).containsOnlyKeys("post");
		assertThat(paths.get("/api/admin/users")).containsOnlyKeys("get");
		assertThat(paths.get("/api/admin/users/{userId}/suspend")).containsOnlyKeys("post");
		assertThat(paths.get("/api/admin/users/{userId}/unsuspend")).containsOnlyKeys("post");
		assertThat(paths.get("/api/admin/bans")).containsOnlyKeys("post");
		assertThat(paths.get("/api/admin/bans/{discordId}")).containsOnlyKeys("delete");
		assertThat(paths.get("/api/admin/users/{userId}/roles")).containsOnlyKeys("get", "put", "delete");
		assertThat(paths.get("/api/admin/audit")).containsOnlyKeys("get");
	}

	/**
	 * Tests the report and admin operations (D-015): typed envelopes, 204s for
	 * actions, the documented 409s and 429, a 403 on every {@code /api/admin}
	 * operation for a caller without the role, and the role named in each summary.
	 */
	@Test
	void adminOperations() {
		assertThat(responses("/api/reports", "post")).containsKeys("204", "400", "403", "404", "429")
				.doesNotContainKey("200");
		assertThat(document.<String>read("$.paths['/api/reports'].post.responses['429'].description"))
				.contains(LimitExceededException.Limit.REPORT_LIMIT.name());
		assertThat(successRef("/api/admin/reports", "get"))
				.isEqualTo("#/components/schemas/ResponseRecordReportPageResponse");
		assertThat(document.<List<String>>read("$.paths['/api/admin/reports'].get.parameters[*].name"))
				.containsExactlyInAnyOrder("status", "cursor", "limit");
		assertThat(successRef("/api/admin/reports/{reportId}", "get"))
				.isEqualTo("#/components/schemas/ResponseRecordReportResponse");
		assertThat(successRef("/api/admin/reports/{reportId}/resolve", "post"))
				.isEqualTo("#/components/schemas/ResponseRecordReportResponse");
		assertThat(conflicts("/api/admin/reports/{reportId}/resolve", "post"))
				.contains(Conflict.REPORT_RESOLVED.name());
		assertThat(successRef("/api/admin/users", "get"))
				.isEqualTo("#/components/schemas/ResponseRecordAdminUserListResponse");
		assertThat(conflicts("/api/admin/users/{userId}/suspend", "post")).contains(Conflict.TARGET_HAS_ROLE.name());
		assertThat(conflicts("/api/admin/bans", "post")).contains(Conflict.TARGET_HAS_ROLE.name());
		assertThat(successRef("/api/admin/users/{userId}/roles", "get"))
				.isEqualTo("#/components/schemas/ResponseRecordRolesResponse");
		assertThat(successRef("/api/admin/users/{userId}/roles", "put"))
				.isEqualTo("#/components/schemas/ResponseRecordRolesResponse");
		assertThat(conflicts("/api/admin/users/{userId}/roles", "put")).contains(Conflict.LAST_ADMIN.name());
		assertThat(conflicts("/api/admin/users/{userId}/roles", "delete")).contains(Conflict.LAST_ADMIN.name());
		assertThat(successRef("/api/admin/audit", "get"))
				.isEqualTo("#/components/schemas/ResponseRecordAuditPageResponse");
		for (var action : List.of(List.of("/api/admin/events/{eventId}/hide", "post"),
				List.of("/api/admin/events/{eventId}/unhide", "post"),
				List.of("/api/admin/events/{eventId}/cancel", "post"),
				List.of("/api/admin/users/{userId}/suspend", "post"),
				List.of("/api/admin/users/{userId}/unsuspend", "post"), List.of("/api/admin/bans", "post"),
				List.of("/api/admin/bans/{discordId}", "delete"), List.of("/api/admin/users/{userId}/roles", "delete")))
			assertThat(responses(action.get(0), action.get(1))).containsKey("204").doesNotContainKey("200");

		document.<Map<String, Map<String, Map<String, Object>>>>read("$.paths").forEach((path, item) -> {
			if (path.startsWith("/api/admin/"))
				item.values().forEach(operation -> {
					assertThat((String) operation.get("summary")).as(path).containsAnyOf("(MODERATOR or ADMIN)",
							"(ADMIN)");
					assertThat(JsonPath.<String>read(operation, "$.responses['403'].description")).as(path)
							.contains("role");
				});
		});
		assertThat(document.<String>read("$.paths['/api/admin/bans'].post.summary")).endsWith("(ADMIN)");
		assertThat(document.<String>read("$.paths['/api/admin/audit'].get.summary")).endsWith("(ADMIN)");
		assertThat(document.<String>read("$.paths['/api/admin/users/{userId}/roles'].put.summary")).endsWith("(ADMIN)");
		assertThat(responses("/api/reports", "post").get("403").toString()).doesNotContain("role");
	}

	/**
	 * Tests the report and admin schemas: what moderators see about a user (never
	 * servers, friends, circles or RSVPs), the report and audit fields, details
	 * with only optional per-action fields, and the length caps.
	 */
	@Test
	void adminSchemas() {
		assertThat(document.<Map<String, Object>>read("$.components.schemas.AdminUserResponse.properties"))
				.containsOnlyKeys("id", "discordId", "username", "globalName", "createdAt", "lastLoginAt",
						"suspendedUntil", "ban", "reportCount");
		assertThat(document.<Integer>read("$.components.schemas.AdminUserListResponse.properties.items.maxItems"))
				.isEqualTo(20);
		assertThat(document.<Map<String, Object>>read("$.components.schemas.ReportRequest.properties"))
				.containsOnlyKeys("targetType", "targetId", "reason", "note");
		assertThat(document.<Integer>read("$.components.schemas.ReportRequest.properties.note.maxLength"))
				.isEqualTo(1000);
		assertThat(document.<List<String>>read("$.components.schemas.ReportRequest.properties.reason.enum"))
				.containsExactly("HARASSMENT", "UNSAFE", "SPAM", "INAPPROPRIATE", "OTHER");
		assertThat(document.<Map<String, Object>>read("$.components.schemas.ReportResponse.properties"))
				.containsOnlyKeys("id", "targetType", "targetId", "targetLabel", "reason", "note", "status",
						"reporterId", "createdAt", "handledBy", "handledAt", "resolutionNote");
		assertThat(document.<List<String>>read("$.components.schemas.ResolveReportRequest.properties.resolution.enum"))
				.containsExactly("ACTIONED", "DISMISSED");
		assertThat(document.<Integer>read("$.components.schemas.ModerationRequest.properties.reason.maxLength"))
				.isEqualTo(500);
		assertThat(document.<List<String>>read("$.components.schemas.RolesRequest.properties.roles.items.enum"))
				.containsExactly("MODERATOR", "ADMIN");
		assertThat(document.<Map<String, Object>>read("$.components.schemas.AuditEntryResponse.properties"))
				.containsOnlyKeys("id", "actorId", "action", "targetType", "targetId", "details", "createdAt");
		assertThat(document.<List<String>>read("$.components.schemas.AuditEntryResponse.properties.action.enum"))
				.containsExactly("ROLES_CHANGED", "SESSIONS_PURGED", "SIGN_IN_REFUSED", "REPORT_RESOLVED",
						"EVENT_HIDDEN", "EVENT_UNHIDDEN", "EVENT_CANCELLED", "USER_SUSPENDED", "USER_UNSUSPENDED",
						"ACCOUNT_BANNED", "ACCOUNT_UNBANNED");
		assertThat(document.<Map<String, Object>>read("$.components.schemas.AuditDetails.properties"))
				.containsOnlyKeys("reason", "until", "resolution", "note", "roles", "cause", "sessions");
		assertThat(document.<Map<String, Object>>read("$.components.schemas.AuditDetails"))
				.doesNotContainKey("required");
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
				.contains(LimitExceededException.Limit.EVENT_LIMIT.name());
		assertThat(document.<String>read("$.paths['/api/events/{eventId}'].patch.responses['409'].description"))
				.contains(Conflict.CAPACITY_BELOW_CONFIRMED.name());
	}

	/**
	 * Tests the RSVP operations (D-013): typed envelopes, bodiless 204s, each
	 * documented 409 errorType, and the spot counts' ETag, If-None-Match and
	 * bodiless 304.
	 */
	@Test
	void rsvpOperations() {
		assertThat(successRef("/api/events/{eventId}/rsvp", "post"))
				.isEqualTo("#/components/schemas/ResponseRecordRsvpResponse");
		assertThat(successRef("/api/events/{eventId}/attendees", "get"))
				.isEqualTo("#/components/schemas/ResponseRecordAttendeesResponse");
		assertThat(successRef("/api/events/{eventId}/spots", "get"))
				.isEqualTo("#/components/schemas/ResponseRecordSpotsResponse");
		assertThat(responses("/api/events/{eventId}/rsvp", "post")).containsKeys("200", "400", "403", "404", "409",
				"429");
		for (var removal : List.of("/api/events/{eventId}/rsvp", "/api/events/{eventId}/rsvps/{userId}"))
			assertThat(responses(removal, "delete")).containsKeys("204", "403", "404", "409").doesNotContainKey("200");
		assertThat(responses("/api/events/{eventId}/attendees", "get")).containsKeys("200", "404")
				.doesNotContainKey("403");
		assertThat(conflicts("/api/events/{eventId}/rsvp", "post")).contains(Conflict.SIGNUP_NOT_OPEN.name(),
				Conflict.SIGNUP_CLOSED.name(), Conflict.RSVP_REMOVED.name());
		assertThat(conflicts("/api/events/{eventId}/rsvp", "delete")).contains(Conflict.HOST_CANNOT_LEAVE.name());
		assertThat(conflicts("/api/events/{eventId}/rsvps/{userId}", "delete"))
				.contains(Conflict.HOST_CANNOT_LEAVE.name());
		assertThat(document.<String>read("$.paths['/api/events/{eventId}/rsvp'].post.responses['429'].description"))
				.contains("TOO_MANY_REQUESTS");

		assertThat(responses("/api/events/{eventId}/spots", "get")).containsKeys("200", "304", "404");
		assertThat(document.<Map<String, Object>>read("$.paths['/api/events/{eventId}/spots'].get.responses['304']"))
				.doesNotContainKey("content");
		assertThat(document
				.<Map<String, Object>>read("$.paths['/api/events/{eventId}/spots'].get.responses['200'].headers"))
				.containsOnlyKeys("ETag");
		assertThat(document
				.<List<String>>read("$.paths['/api/events/{eventId}/spots'].get.parameters[?(@.in == 'header')].name"))
				.containsExactly("If-None-Match");
	}

	/**
	 * Tests the RSVP schemas: the caller's own status and position, a waitlist only
	 * the host gets, and people as public profiles.
	 */
	@Test
	void rsvpSchemas() {
		Map<String, Map<String, Object>> rsvp = document.read("$.components.schemas.RsvpResponse.properties");
		assertThat(rsvp).containsOnlyKeys("status", "position", "offerExpiresAt");
		assertThat(rsvp.get("status").get("enum")).isEqualTo(List.of("CONFIRMED", "WAITLISTED", "OFFERED"));
		assertThat((List<String>) rsvp.get("position").get("type")).contains("null");
		assertThat((List<String>) rsvp.get("offerExpiresAt").get("type")).contains("null");

		Map<String, Map<String, Object>> spots = document.read("$.components.schemas.SpotsResponse.properties");
		assertThat(spots).containsOnlyKeys("capacity", "confirmed", "waitlisted", "spotsLeft", "myStatus", "myPosition",
				"offerExpiresAt");
		assertThat(spots.get("myStatus").get("enum")).isEqualTo(MY_STATUSES);
		for (var nullable : List.of("capacity", "spotsLeft", "myPosition", "offerExpiresAt"))
			assertThat((List<String>) spots.get(nullable).get("type")).contains("null");

		Map<String, Map<String, Object>> attendees = document.read("$.components.schemas.AttendeesResponse.properties");
		assertThat(attendees).containsOnlyKeys("confirmed", "waitlist", "offered");
		assertThat((List<String>) attendees.get("waitlist").get("type")).contains("null");
		assertThat((List<String>) attendees.get("offered").get("type")).contains("null");
		assertThat(document.<String>read("$.components.schemas.AttendeesResponse.properties.confirmed.items.$ref"))
				.isEqualTo("#/components/schemas/PersonResponse");
	}

	/**
	 * Tests the offer, inbox and push operations (D-014): typed envelopes, bodiless
	 * 204s with request bodies, the accept's 409 and the subscription allowlist.
	 */
	@Test
	void notifyOperations() {
		assertThat(successRef("/api/events/{eventId}/rsvp/accept", "post"))
				.isEqualTo("#/components/schemas/ResponseRecordRsvpResponse");
		assertThat(responses("/api/events/{eventId}/rsvp/accept", "post")).containsKeys("200", "403", "404", "409");
		assertThat(conflicts("/api/events/{eventId}/rsvp/accept", "post")).contains(Conflict.OFFER_EXPIRED.name(),
				Conflict.NO_OFFER.name());
		assertThat(successRef("/api/notifications", "get"))
				.isEqualTo("#/components/schemas/ResponseRecordNotificationPageResponse");
		assertThat(document.<List<String>>read("$.paths['/api/notifications'].get.parameters[*].name"))
				.containsExactlyInAnyOrder("cursor", "limit");
		assertThat(successRef("/api/push/key", "get")).isEqualTo("#/components/schemas/ResponseRecordPushKeyResponse");
		assertThat(responses("/api/push/key", "get")).containsKeys("200", "404");
		for (var write : List.of(List.of("/api/notifications/read", "post"), List.of("/api/push/subscriptions", "post"),
				List.of("/api/push/subscriptions", "delete"))) {
			assertThat(responses(write.get(0), write.get(1))).containsKeys("204", "400", "403")
					.doesNotContainKey("200");
			assertThat(document.<Object>read("$.paths['" + write.get(0) + "']." + write.get(1) + ".requestBody"))
					.isNotNull();
		}
		assertThat(document.<String>read("$.components.schemas.PushSubscriptionRequest.properties.endpoint.pattern"))
				.isEqualTo(PushSubscriptionRequest.ENDPOINT_PATTERN);
		assertThat(document.<List<String>>read("$.components.schemas.PushSubscriptionRequest.required"))
				.containsExactlyInAnyOrder("endpoint", "keys");
	}

	/**
	 * Tests the inbox schemas: every notification type, a payload with only
	 * optional per-type fields and no location or name, and the page's unread
	 * count.
	 */
	@Test
	void notificationSchemas() {
		Map<String, Map<String, Object>> item = document.read("$.components.schemas.NotificationResponse.properties");
		assertThat(item).containsOnlyKeys("id", "type", "eventId", "payload", "createdAt", "read");
		assertThat(item.get("type").get("enum"))
				.isEqualTo(List.of("SPOT_OFFERED", "SPOT_CONFIRMED", "EVENT_CHANGED", "EVENT_CANCELLED",
						"EVENT_REMINDER", "FRIEND_ACCEPTED", "REPORT_RECEIVED", "REPORT_REVIEWED", "EVENT_HIDDEN"));
		assertThat((List<String>) item.get("eventId").get("type")).contains("null");

		Map<String, Map<String, Object>> payload = document.read("$.components.schemas.NotificationPayload.properties");
		assertThat(payload).containsOnlyKeys("eventTitle", "offerExpiresAt", "startsAt", "changes", "friendId",
				"reportId", "targetType", "reason");
		assertThat(payload.get("reason")).containsEntry("maxLength", 500);
		assertThat(document.<Map<String, Object>>read("$.components.schemas.NotificationPayload"))
				.doesNotContainKey("required");
		assertThat(
				document.<List<String>>read("$.components.schemas.NotificationPayload.properties.changes.items.enum"))
				.containsExactly("START", "END", "LOCATION");

		Map<String, Map<String, Object>> page = document
				.read("$.components.schemas.NotificationPageResponse.properties");
		assertThat(page).containsOnlyKeys("items", "nextCursor", "unreadCount");
		assertThat(page.get("unreadCount")).containsEntry("maximum", 100);
		assertThat((List<String>) page.get("nextCursor").get("type")).contains("null");
	}

	/**
	 * Tests the event schemas: {@code isHost} under that name, a nullable location
	 * and audience, and typed audience entries with no free-text reference.
	 */
	@Test
	void eventSchemas() {
		Map<String, Map<String, Object>> properties = document.read("$.components.schemas.EventResponse.properties");
		assertThat(properties).containsOnlyKeys("id", "host", "title", "description", "location", "locationHidden",
				"locationVisibility", "tag", "startsAt", "endsAt", "timeZone", "signupOpensAt", "capacity",
				"confirmWindowMinutes", "status", "isHost", "myStatus", "audience", "hiddenReason");
		assertThat((List<String>) properties.get("hiddenReason").get("type")).contains("null");
		assertThat(properties.get("myStatus").get("enum")).isEqualTo(MY_STATUSES);
		assertThat(document.<List<String>>read("$.components.schemas.EventResponse.required"))
				.contains("confirmWindowMinutes");
		assertThat(
				document.<Map<String, Object>>read("$.components.schemas.EventRequest.properties.confirmWindowMinutes"))
				.containsEntry("minimum", 10).containsEntry("maximum", 1440);
		assertThat(document.<List<String>>read("$.components.schemas.EventRequest.required"))
				.doesNotContain("confirmWindowMinutes");
		assertThat(document.<Integer>read("$.components.schemas.EventRequest.properties.capacity.minimum"))
				.isEqualTo(2);
		assertThat((List<String>) properties.get("location").get("type")).contains("null");
		assertThat((List<String>) properties.get("audience").get("type")).contains("null");
		assertThat(document.<Map<String, Object>>read("$.components.schemas.AudienceEntryRequest.properties"))
				.containsOnlyKeys("type", "guildId", "circleId", "userId");
		assertThat(document.<Map<String, Object>>read("$.components.schemas.AudienceEntryResponse.properties"))
				.containsOnlyKeys("type", "guildId", "circleId", "userId", "label");
		assertThat(document.<Integer>read("$.components.schemas.EventRequest.properties.audience.maxItems"))
				.isEqualTo(50);
		assertThat(document
				.<List<String>>read("$.paths['/api/events'].get.parameters[?(@.name == 'scope')].schema.enum[*]"))
				.containsExactly("feed", "hosting", "attending");
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

		assertThat(responses).hasSize(55).allSatisfy(byStatus -> {
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
				"roles", "mfaEnabled", "mfaRequired");
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
	 * Returns the description of an operation's 409 response, which names its
	 * domain errorTypes.
	 *
	 * @param path   the path
	 * @param method the HTTP method, in lower case
	 * @return the description
	 */
	private String conflicts(String path, String method) {
		return document.read("$.paths['" + path + "']." + method + ".responses['409'].description");
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
