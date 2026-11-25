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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for how {@link EventResponse} serializes: the field names the
 * contract promises, and nulls for what the viewer may not see.
 *
 * @author mcawful
 */
class EventResponseTest {
	/**
	 * Tests that a non-host's view names {@code isHost} as such and carries no
	 * location or audience.
	 */
	@Test
	void nonHostViewHidesLocationAndAudience() {
		var host = new PersonResponse(UUID.randomUUID(), "Con Goer", "https://cdn.discordapp.com/embed/avatars/0.png");
		var event = new EventResponse(UUID.randomUUID(), host, "Cosplay photo walk", null, null, true,
				EventResponse.LocationVisibility.CONFIRMED, null, Instant.parse("2026-12-01T18:00:00Z"), null,
				"America/Chicago", null, 20, 30, EventResponse.Status.PUBLISHED, false, MyStatus.NONE, null, null);

		JsonNode json = JsonMapper.builder().build().valueToTree(event);

		assertThat(json.propertyNames())
				.contains("isHost", "host", "locationHidden", "audience", "location", "myStatus")
				.doesNotContain("host_", "hostId");
		assertThat(json.get("isHost").asBoolean()).isFalse();
		assertThat(json.get("host").get("displayName").asString()).isEqualTo("Con Goer");
		assertThat(json.get("location").isNull()).isTrue();
		assertThat(json.get("audience").isNull()).isTrue();
		assertThat(json.get("myStatus").asString()).isEqualTo("NONE");
		assertThat(json.get("startsAt").asString()).isEqualTo("2026-12-01T18:00:00Z");
	}
}
