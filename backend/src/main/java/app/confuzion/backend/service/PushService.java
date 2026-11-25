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

import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.confuzion.backend.config.VapidKeys;
import app.confuzion.backend.model.record.PushKeyResponse;
import app.confuzion.backend.model.record.PushSubscriptionRequest;
import app.confuzion.backend.repository.PushRepository;

/**
 * The caller's Web Push subscriptions and the server's public key (D-014 rules
 * 13 and 14). Every change is to the caller's own subscriptions, except that
 * subscribing with an endpoint someone else had moves it to the caller.
 *
 * @author mcawful
 */
@Service
public class PushService {
	/**
	 * The most subscriptions a user keeps. An 11th replaces their oldest.
	 */
	static final int MAX_SUBSCRIPTIONS = 10;

	/**
	 * The {@link PushRepository}.
	 */
	private final PushRepository pushRepository;

	/**
	 * The {@link VapidKeys}.
	 */
	private final VapidKeys vapidKeys;

	/**
	 * Creates the service.
	 *
	 * @param pushRepository the {@link PushRepository}
	 * @param vapidKeys      the {@link VapidKeys}
	 */
	public PushService(PushRepository pushRepository, VapidKeys vapidKeys) {
		this.pushRepository = pushRepository;
		this.vapidKeys = vapidKeys;
	}

	/**
	 * Returns the server's VAPID public key.
	 *
	 * @return the {@link PushKeyResponse}
	 * @throws NoSuchElementException when push is off (no VAPID keys)
	 */
	public PushKeyResponse getKey() {
		return new PushKeyResponse(vapidKeys.publicKey().orElseThrow());
	}

	/**
	 * Saves a subscription for the caller, as their newest, and drops their oldest
	 * beyond {@link #MAX_SUBSCRIPTIONS}. The caller's row is locked first, so
	 * concurrent subscribes can't both slip past the cap.
	 *
	 * @param userId  the caller's ID
	 * @param request the {@link PushSubscriptionRequest}, already validated against
	 *                the push service allowlist
	 */
	@Transactional
	public void subscribe(UUID userId, PushSubscriptionRequest request) {
		pushRepository.lockUser(userId);
		pushRepository.upsert(userId, request.endpoint(), request.keys().p256dh(), request.keys().auth());
		pushRepository.trim(userId, MAX_SUBSCRIPTIONS);
	}

	/**
	 * Removes one of the caller's subscriptions. Nothing happens when the caller
	 * doesn't have it.
	 *
	 * @param userId   the caller's ID
	 * @param endpoint the subscription's endpoint
	 */
	@Transactional
	public void unsubscribe(UUID userId, String endpoint) {
		pushRepository.delete(userId, endpoint);
	}

	/**
	 * Removes every subscription the user has, for "log out everywhere" (D-014 rule
	 * 18).
	 *
	 * @param userId the user's ID
	 */
	@Transactional
	public void unsubscribeAll(UUID userId) {
		pushRepository.deleteAll(userId);
	}
}
