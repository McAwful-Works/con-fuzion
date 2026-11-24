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

import java.time.Instant;

import app.confuzion.backend.model.ResponseBodyObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/**
 * What the invite accept page shows before the user accepts, returned by
 * {@code GET /api/friends/invites/{token}}. It names the inviter but not their
 * user ID, since the two aren't friends yet.
 *
 * @param inviter   who made the invite
 * @param expiresAt when the invite stops working
 * @author mcawful
 */
public record InvitePreviewResponse(@Schema(requiredMode = RequiredMode.REQUIRED) Inviter inviter,
		@Schema(requiredMode = RequiredMode.REQUIRED) Instant expiresAt) implements ResponseBodyObject {
	/**
	 * The inviter's name and avatar.
	 *
	 * @param displayName the Discord display name, or the username when the user
	 *                    has none
	 * @param avatarUrl   the Discord CDN avatar URL (Discord's default avatar when
	 *                    the user has none)
	 * @author mcawful
	 */
	public record Inviter(@Schema(requiredMode = RequiredMode.REQUIRED, example = "Con Goer") String displayName,
			@Schema(requiredMode = RequiredMode.REQUIRED, example = "https://cdn.discordapp.com/embed/avatars/0.png") String avatarUrl) {
	}
}
