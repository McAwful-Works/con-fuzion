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

import static app.confuzion.backend.controller.ErrorEnvelopeAssert.assertError;
import static app.confuzion.backend.controller.ErrorEnvelopeAssert.json;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import app.confuzion.backend.model.record.PushKeyResponse;
import app.confuzion.backend.model.record.PushSubscriptionRequest;
import app.confuzion.backend.service.PushService;

/**
 * Unit tests for the {@link PushController}: it maps to the {@link PushService}
 * for the caller, and a subscription outside the push service allowlist, or
 * with keys of the wrong size, is a 400 before the service is called.
 *
 * @author mcawful
 */
class PushControllerTest {
	/**
	 * The signed-in user.
	 */
	private static final Authentication AUTHENTICATION = UsernamePasswordAuthenticationToken
			.authenticated(UUID.randomUUID().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

	/**
	 * The caller's ID.
	 */
	private static final UUID USER_ID = UUID.fromString(AUTHENTICATION.getName());

	/**
	 * A browser's P-256 public key: 65 bytes of base64url.
	 */
	static final String P256DH = "B" + "A".repeat(86);

	/**
	 * A browser's authentication secret: 16 bytes of base64url.
	 */
	static final String AUTH = "a".repeat(22);

	/**
	 * The mocked {@link PushService}.
	 */
	private final PushService pushService = mock(PushService.class);

	/**
	 * The {@link MockMvc}.
	 */
	private final MockMvc mockMvc = ErrorEnvelopeAssert.mockMvc(new PushController(pushService));

	/**
	 * Returns a subscription body.
	 *
	 * @param endpoint the endpoint
	 * @param p256dh   the public key
	 * @param auth     the authentication secret
	 * @return the JSON
	 */
	static String subscription(String endpoint, String p256dh, String auth) {
		return "{\"endpoint\":\"" + endpoint + "\",\"expirationTime\":null,\"keys\":{\"p256dh\":\"" + p256dh
				+ "\",\"auth\":\"" + auth + "\"}}";
	}

	/**
	 * Tests that the key is returned in its envelope, and is a 404 when push is
	 * off.
	 *
	 * @throws Exception on failure
	 */
	@Test
	void testKey() throws Exception {
		when(pushService.getKey()).thenReturn(new PushKeyResponse(P256DH));
		mockMvc.perform(get("/api/push/key").principal(AUTHENTICATION)).andExpect(status().isOk())
				.andExpect(jsonPath("$.responseType").value("PUSH_KEY"))
				.andExpect(jsonPath("$.body.publicKey").value(P256DH));

		when(pushService.getKey()).thenThrow(new NoSuchElementException());
		assertError(mockMvc, get("/api/push/key").principal(AUTHENTICATION), HttpStatus.NOT_FOUND);
	}

	/**
	 * Tests that unsubscribing removes the caller's subscription with that
	 * endpoint.
	 *
	 * @throws Exception on failure
	 */
	@Test
	void testUnsubscribe() throws Exception {
		mockMvc.perform(
				json(delete("/api/push/subscriptions"), "{\"endpoint\":\"https://fcm.googleapis.com/fcm/send/x\"}")
						.principal(AUTHENTICATION))
				.andExpect(status().isNoContent());
		verify(pushService).unsubscribe(USER_ID, "https://fcm.googleapis.com/fcm/send/x");
	}

	/**
	 * Tests that a subscription on each allowed push service is saved for the
	 * caller, padded keys included.
	 *
	 * @param endpoint the endpoint
	 * @throws Exception on failure
	 */
	@ParameterizedTest
	@ValueSource(strings = { "https://fcm.googleapis.com/fcm/send/dQw4w9WgXcQ:APA91bH-x_y",
			"https://updates.push.services.mozilla.com/wpush/v2/gAAAAABk",
			"https://web.push.apple.com/QGuQyavXutnMH3a9S6Pd2tmAYYgj",
			"https://wns2-par02p.notify.windows.com/w/?token=BQYAAAB%2bXyz" })
	void testSubscribesOnAllowedPushServices(String endpoint) throws Exception {
		mockMvc.perform(subscribe(subscription(endpoint, P256DH, AUTH))).andExpect(status().isNoContent());
		mockMvc.perform(subscribe(subscription(endpoint, P256DH + "=", AUTH + "=="))).andExpect(status().isNoContent());
		verify(pushService).subscribe(USER_ID,
				new PushSubscriptionRequest(endpoint, new PushSubscriptionRequest.Keys(P256DH, AUTH)));
	}

	/**
	 * Returns subscription bodies that must be refused: endpoints off the allowlist
	 * or not https, and keys of the wrong size or alphabet.
	 *
	 * @return the bodies
	 */
	static Stream<String> badSubscriptions() {
		var fcm = "https://fcm.googleapis.com/fcm/send/x";
		return Stream.of(subscription("http://fcm.googleapis.com/fcm/send/x", P256DH, AUTH),
				subscription("https://example.com/push", P256DH, AUTH),
				subscription("https://fcm.googleapis.com.example.com/x", P256DH, AUTH),
				subscription("https://fcm.googleapis.com@example.com/x", P256DH, AUTH),
				subscription("https://fcm.googleapis.com:8443/x", P256DH, AUTH),
				subscription("https://fcm.googleapis.com", P256DH, AUTH),
				subscription("https://localhost/x", P256DH, AUTH), subscription("https://127.0.0.1/x", P256DH, AUTH),
				subscription("https://[::1]/x", P256DH, AUTH),
				subscription("https://169.254.169.254/latest/meta-data", P256DH, AUTH),
				subscription("https://evil.example/.notify.windows.com/x", P256DH, AUTH),
				subscription("https://fcm.googleapis.com/a b", P256DH, AUTH),
				subscription("https://fcm.googleapis.com/" + "a".repeat(2048), P256DH, AUTH),
				subscription(fcm, P256DH.substring(1), AUTH), subscription(fcm, P256DH + "A", AUTH),
				subscription(fcm, P256DH.replace('A', '+'), AUTH), subscription(fcm, P256DH, AUTH.substring(1)),
				subscription(fcm, P256DH, AUTH + "="), "{\"endpoint\":\"" + fcm + "\"}",
				"{\"keys\":{\"p256dh\":\"" + P256DH + "\",\"auth\":\"" + AUTH + "\"}}");
	}

	/**
	 * Tests that a bad subscription is a 400.
	 *
	 * @param body the body
	 */
	@ParameterizedTest
	@MethodSource("badSubscriptions")
	void testRejectsBadSubscriptions(String body) throws Exception {
		assertError(mockMvc, subscribe(body), HttpStatus.BAD_REQUEST);
		verify(pushService, never()).subscribe(any(), any());
	}

	/**
	 * Tests that unsubscribing needs an endpoint of at most 2,048 characters.
	 *
	 * @param body the body
	 */
	@ParameterizedTest
	@ValueSource(strings = { "{}", "{\"endpoint\":\" \"}", "{\"endpoint\":null}" })
	void testRejectsBadUnsubscribes(String body) throws Exception {
		assertError(mockMvc, json(delete("/api/push/subscriptions"), body).principal(AUTHENTICATION),
				HttpStatus.BAD_REQUEST);
		assertError(mockMvc,
				json(delete("/api/push/subscriptions"),
						"{\"endpoint\":\"https://fcm.googleapis.com/" + "a".repeat(2048) + "\"}")
						.principal(AUTHENTICATION),
				HttpStatus.BAD_REQUEST);
	}

	/**
	 * Builds a subscribe request.
	 *
	 * @param body the JSON body
	 * @return the request
	 */
	private static MockHttpServletRequestBuilder subscribe(String body) {
		return json(post("/api/push/subscriptions"), body).principal(AUTHENTICATION);
	}
}
