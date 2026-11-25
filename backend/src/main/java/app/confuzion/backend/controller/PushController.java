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

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.confuzion.backend.model.record.PushEndpointRequest;
import app.confuzion.backend.model.record.PushKeyResponse;
import app.confuzion.backend.model.record.PushSubscriptionRequest;
import app.confuzion.backend.model.record.ResponseRecord;
import app.confuzion.backend.service.PushService;
import app.confuzion.backend.util.HttpUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * The {@link RestController} for Web Push: the server's public key and the
 * caller's browser subscriptions (D-014). Pushes go out only while the user's
 * {@code pushEnabled} setting is on.
 *
 * @author mcawful
 */
@RestController
@RequestMapping("/api/push")
@Tag(name = "push", description = "Web Push key and browser subscriptions")
public class PushController {
	/**
	 * The {@link PushService}.
	 */
	private final PushService pushService;

	/**
	 * Creates the controller.
	 *
	 * @param pushService the {@link PushService}
	 */
	public PushController(PushService pushService) {
		this.pushService = pushService;
	}

	/**
	 * Returns the server's VAPID public key.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @return the {@link PushKeyResponse} envelope
	 */
	@GetMapping("/key")
	@Operation(summary = "Get the Web Push public key", description = "The VAPID public key, the same for every user. "
			+ "It isn't a secret, but like every API route it needs a session.")
	@ApiResponse(responseCode = "200", description = "OK")
	@ApiResponse(responseCode = "404", description = "Push isn't set up on this server (no VAPID keys), so the client "
			+ "hides the push setting.")
	public ResponseRecord<PushKeyResponse> getKey(@Parameter(hidden = true) Authentication authentication) {
		return new ResponseRecord<>(pushService.getKey());
	}

	/**
	 * Saves a browser's push subscription for the caller.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param request        the {@link PushSubscriptionRequest}
	 */
	@PostMapping("/subscriptions")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Subscribe this browser to push", description = "Send PushSubscription.toJSON(). An endpoint "
			+ "belongs to whoever subscribed with it last, so subscribing again moves it to the caller and replaces "
			+ "its keys; a browser shared between accounts never gets the previous user's pushes. A user has at "
			+ "most 10 subscriptions: an 11th replaces their oldest. This doesn't turn pushEnabled on (PATCH "
			+ "/api/me does).")
	@ApiResponse(responseCode = "204", description = "Saved.")
	@ApiResponse(responseCode = "400", description = "The endpoint isn't an https URL on a known push service, or a key "
			+ "isn't the right length of base64url.")
	public void subscribe(@Parameter(hidden = true) Authentication authentication,
			@Valid @RequestBody PushSubscriptionRequest request) {
		pushService.subscribe(HttpUtil.userId(authentication), request);
	}

	/**
	 * Removes one of the caller's push subscriptions.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param request        the {@link PushEndpointRequest}
	 */
	@DeleteMapping("/subscriptions")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Unsubscribe a browser from push", description = "Removes the caller's subscription with this "
			+ "endpoint, for example on sign-out or after PushSubscription.unsubscribe(). Another user's "
			+ "subscription is never touched. An endpoint the caller doesn't have is also a 204.")
	@ApiResponse(responseCode = "204", description = "Removed, or wasn't subscribed.")
	public void unsubscribe(@Parameter(hidden = true) Authentication authentication,
			@Valid @RequestBody PushEndpointRequest request) {
		pushService.unsubscribe(HttpUtil.userId(authentication), request.endpoint());
	}
}
