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

import java.util.List;
import java.util.UUID;

import app.confuzion.backend.model.ResponseBodyObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/**
 * The signed-in user's own profile, returned by {@code GET} and
 * {@code PATCH /api/me}. It never carries an email, because we don't request
 * one.
 *
 * @param id          the user's Con-Fuzion ID
 * @param username    the Discord username
 * @param globalName  the Discord display name, or {@code null} when the user
 *                    has none
 * @param avatarUrl   the Discord CDN avatar URL (Discord's default avatar when
 *                    the user has none)
 * @param timeZone    the user's IANA time zone ID, or {@code null} until they
 *                    pick one
 * @param pushEnabled whether the user wants push notifications
 * @param roles       the roles in effect for this session, which always include
 *                    {@link Role#USER}. A granted role is in effect only while
 *                    Discord reported MFA on at sign-in (D-015)
 * @param mfaEnabled  whether the user's Discord account has MFA on, which
 *                    moderator and admin powers require
 * @param mfaRequired whether the user holds a granted role that isn't in effect
 *                    because MFA is off, so the client shows the "turn on
 *                    Discord 2FA" note
 * @author mcawful
 */
public record MeResponse(@Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
		@Schema(requiredMode = RequiredMode.REQUIRED, example = "con_goer") String username,
		@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, example = "Con Goer") String globalName,
		@Schema(requiredMode = RequiredMode.REQUIRED, example = "https://cdn.discordapp.com/embed/avatars/0.png") String avatarUrl,
		@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, example = "America/Chicago") String timeZone,
		@Schema(requiredMode = RequiredMode.REQUIRED) boolean pushEnabled,
		@Schema(requiredMode = RequiredMode.REQUIRED, description = "The roles in effect for this session. Always includes USER. MODERATOR and ADMIN apply only while Discord reported MFA on at sign-in (D-015).") List<Role> roles,
		@Schema(requiredMode = RequiredMode.REQUIRED) boolean mfaEnabled,
		@Schema(requiredMode = RequiredMode.REQUIRED, description = "True when the user holds MODERATOR or ADMIN but it isn't in effect because their Discord account has MFA off. Show the \"turn on Discord 2FA\" note; it takes effect at the next sign-in after they turn it on.") boolean mfaRequired)
		implements ResponseBodyObject {
	/**
	 * A user role. Everyone has {@code USER}; an admin grants the others (plan
	 * B12).
	 *
	 * @author mcawful
	 */
	public enum Role {
		/**
		 * Every signed-in user.
		 */
		USER,

		/**
		 * Works the report queue and can hide or cancel events.
		 */
		MODERATOR,

		/**
		 * Can also ban accounts and change roles.
		 */
		ADMIN
	}
}
