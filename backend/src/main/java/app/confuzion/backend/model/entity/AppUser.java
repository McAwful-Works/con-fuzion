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
package app.confuzion.backend.model.entity;

import java.util.UUID;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A signed-in user ({@code app_user}). Rows are created and refreshed only by
 * the login upsert in {@code AppUserRepository}; this entity reads them and
 * changes the user's own settings. Columns that nothing reads yet aren't
 * mapped.
 *
 * @author mcawful
 */
@Entity
@Table(name = "app_user")
// Write only the changed columns, so concurrent updates of different settings don't overwrite each other.
@DynamicUpdate
@Getter
@Setter
@NoArgsConstructor
public class AppUser {
	/**
	 * The Con-Fuzion user ID.
	 */
	@Id
	private UUID id;

	/**
	 * The Discord user ID.
	 */
	@Column(name = "discord_id", nullable = false, updatable = false)
	private long discordId;

	/**
	 * The Discord username.
	 */
	@Column(nullable = false, updatable = false)
	private String username;

	/**
	 * The Discord display name, or {@code null}.
	 */
	@Column(name = "global_name", updatable = false)
	private String globalName;

	/**
	 * The Discord avatar hash, or {@code null}.
	 */
	@Column(name = "avatar_hash", updatable = false)
	private String avatarHash;

	/**
	 * Whether the Discord account had MFA on at the last login.
	 */
	@Column(name = "mfa_enabled", nullable = false, updatable = false)
	private boolean mfaEnabled;

	/**
	 * The IANA time zone ID, or {@code null} until the user picks one.
	 */
	@Column(name = "time_zone")
	private String timeZone;

	/**
	 * Whether the user wants push notifications.
	 */
	@Column(name = "push_enabled", nullable = false)
	private boolean pushEnabled;
}
