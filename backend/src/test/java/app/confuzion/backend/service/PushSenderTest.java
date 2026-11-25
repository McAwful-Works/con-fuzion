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
package app.confuzion.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import app.confuzion.backend.model.record.NotificationPayload;
import app.confuzion.backend.model.record.NotificationPayload.Change;
import app.confuzion.backend.model.record.NotificationResponse.Type;

/**
 * Unit tests for the {@link PushSender}'s rules that need no database: the
 * second host check, {@code Retry-After}, the lock-screen text and the HTTP
 * client's settings.
 *
 * @author mcawful
 */
class PushSenderTest {
	/**
	 * Tests that endpoints on the allowed push services pass, with the host
	 * lower-cased.
	 *
	 * @param endpoint the endpoint
	 */
	@ParameterizedTest
	@ValueSource(strings = { "https://fcm.googleapis.com/fcm/send/abc", "https://FCM.googleapis.com/fcm/send/abc",
			"https://updates.push.services.mozilla.com/wpush/v2/abc", "https://web.push.apple.com/abc",
			"https://wns2-par02p.notify.windows.com/w/?token=abc" })
	void testAllowsKnownPushServices(String endpoint) {
		assertThat(PushSender.allowedHost(endpoint)).isNotNull().isLowerCase();
	}

	/**
	 * Tests that plain http, IP addresses, localhost, unknown hosts, look-alikes,
	 * user info, ports and junk are all refused.
	 *
	 * @param endpoint the endpoint
	 */
	@ParameterizedTest
	@ValueSource(strings = { "http://fcm.googleapis.com/fcm/send/abc", "https://127.0.0.1/x", "https://10.0.0.5/x",
			"https://169.254.169.254/latest/meta-data", "https://[::1]/x", "https://localhost/x",
			"https://example.com/push", "https://fcm.googleapis.com.example.com/x", "https://evil.fcm.googleapis.com/x",
			"https://a.b.notify.windows.com/x", "https://notify.windows.com/x", "https://user@fcm.googleapis.com/x",
			"https://fcm.googleapis.com:8443/x", "https://fcm.googleapis.com:443/x", "ftp://fcm.googleapis.com/x",
			"fcm.googleapis.com/x", "https:///x", "https://fcm.googleapis.com/a b", "" })
	void testRefusesEverythingElse(String endpoint) {
		assertThat(PushSender.allowedHost(endpoint)).isNull();
	}

	/**
	 * Tests that {@code Retry-After} reads seconds and HTTP dates, falls back to
	 * the default, and is kept between a second and an hour.
	 */
	@Test
	void testRetryAfter() {
		var now = Instant.parse("2026-11-25T12:00:00Z");
		assertThat(PushSender.retryAfter("120", now)).isEqualTo(Duration.ofSeconds(120));
		assertThat(PushSender.retryAfter(" 30 ", now)).isEqualTo(Duration.ofSeconds(30));
		assertThat(PushSender.retryAfter(
				DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(300).atZone(ZoneOffset.UTC)), now))
				.isEqualTo(Duration.ofSeconds(300));
		assertThat(PushSender.retryAfter(null, now)).isEqualTo(PushSender.DEFAULT_RETRY_AFTER);
		assertThat(PushSender.retryAfter("soon", now)).isEqualTo(PushSender.DEFAULT_RETRY_AFTER);
		assertThat(PushSender.retryAfter("0", now)).isEqualTo(Duration.ofSeconds(1));
		assertThat(PushSender.retryAfter("-5", now)).isEqualTo(Duration.ofSeconds(1));
		assertThat(PushSender.retryAfter("86400", now)).isEqualTo(PushSender.MAX_RETRY_AFTER);
	}

	/**
	 * Tests the lock-screen text for every type: the title and what happened, and
	 * never the friend's ID.
	 */
	@Test
	void testLockScreenText() {
		var title = new NotificationPayload("Photo walk", null, null, null, null);
		assertThat(PushSender.text(Type.SPOT_OFFERED,
				new NotificationPayload("Photo walk", Instant.now(), null, null, null)))
				.isEqualTo("A spot opened up for you at Photo walk. Accept it before the offer runs out.");
		assertThat(PushSender.text(Type.SPOT_CONFIRMED, title)).isEqualTo("You're confirmed for Photo walk.");
		assertThat(PushSender.text(Type.EVENT_CANCELLED, title)).isEqualTo("Photo walk was cancelled.");
		assertThat(PushSender.text(Type.EVENT_REMINDER,
				new NotificationPayload("Photo walk", null, Instant.now(), null, null)))
				.isEqualTo("Photo walk starts in about an hour.");
		assertThat(PushSender.text(Type.EVENT_CHANGED, changed(List.of(Change.LOCATION))))
				.isEqualTo("Photo walk has a new location.");
		assertThat(PushSender.text(Type.EVENT_CHANGED, changed(List.of(Change.START, Change.LOCATION))))
				.isEqualTo("Photo walk has a new start time and location.");
		assertThat(PushSender.text(Type.EVENT_CHANGED, changed(List.of(Change.START, Change.END, Change.LOCATION))))
				.isEqualTo("Photo walk has a new start time, end time and location.");
		assertThat(PushSender.text(Type.EVENT_CHANGED, changed(List.of()))).isEqualTo("Photo walk was updated.");
		var friend = UUID.randomUUID();
		assertThat(PushSender.text(Type.FRIEND_ACCEPTED, new NotificationPayload(null, null, null, null, friend)))
				.isEqualTo("Someone accepted your friend invite.").doesNotContain(friend.toString());
	}

	/**
	 * Tests that the push services' client never follows a redirect and has a
	 * connect timeout.
	 */
	@Test
	void testHttpClientNeverFollowsRedirects() {
		var client = PushSender.httpClient();
		assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
		assertThat(client.connectTimeout()).contains(PushSender.CONNECT_TIMEOUT);
	}

	/**
	 * Returns an {@code EVENT_CHANGED} payload.
	 *
	 * @param changes what changed
	 * @return the payload
	 */
	private static NotificationPayload changed(List<Change> changes) {
		return new NotificationPayload("Photo walk", Instant.now(), null, changes, null);
	}
}
