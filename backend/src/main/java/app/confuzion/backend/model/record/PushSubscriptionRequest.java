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

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A browser's Web Push subscription, as {@code PushSubscription.toJSON()} gives
 * it. Other fields, such as {@code expirationTime}, are ignored.
 *
 * @param endpoint the push service URL
 * @param keys     the subscription's keys
 * @author mcawful
 */
public record PushSubscriptionRequest(
		@Schema(requiredMode = RequiredMode.REQUIRED, maxLength = PushSubscriptionRequest.MAX_ENDPOINT, pattern = PushSubscriptionRequest.ENDPOINT_PATTERN, description = "An https URL on a known push service: FCM (Chrome and most Chromium browsers), Mozilla, Apple or Windows (WNS). Any other host is a 400, so the server never sends requests anywhere else.", example = "https://fcm.googleapis.com/fcm/send/abc123") @NotNull @Size(max = PushSubscriptionRequest.MAX_ENDPOINT) @Pattern(regexp = PushSubscriptionRequest.ENDPOINT_PATTERN) String endpoint,
		@Schema(requiredMode = RequiredMode.REQUIRED) @NotNull @Valid Keys keys) {

	/**
	 * The longest endpoint accepted.
	 */
	public static final int MAX_ENDPOINT = 2048;

	/**
	 * An https URL on an allowed push service host, then a path of printable ASCII.
	 * Allowing only these hosts keeps the sender from being pointed at any other
	 * server (D-014).
	 */
	public static final String ENDPOINT_PATTERN = "^https://(fcm\\.googleapis\\.com|updates\\.push\\.services\\.mozilla\\.com"
			+ "|web\\.push\\.apple\\.com|[a-z0-9-]{1,63}\\.notify\\.windows\\.com)/[!-~]*$";

	/**
	 * A subscription's keys, base64url as the browser gives them.
	 *
	 * @param p256dh the browser's P-256 public key
	 * @param auth   the 16-byte authentication secret
	 * @author mcawful
	 */
	@Schema(name = "PushSubscriptionKeys")
	public record Keys(
			@Schema(requiredMode = RequiredMode.REQUIRED, pattern = "^[A-Za-z0-9_-]{87}=?$", description = "The browser's P-256 public key (65 bytes), base64url.") @NotNull @Pattern(regexp = "^[A-Za-z0-9_-]{87}=?$") String p256dh,
			@Schema(requiredMode = RequiredMode.REQUIRED, pattern = "^[A-Za-z0-9_-]{22}(==)?$", description = "The authentication secret (16 bytes), base64url.") @NotNull @Pattern(regexp = "^[A-Za-z0-9_-]{22}(==)?$") String auth) {
	}
}
