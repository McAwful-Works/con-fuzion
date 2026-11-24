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

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One Discord server a user was in at their last login sync
 * ({@code user_guild}). The user is discoverable there only once they turn
 * {@link #discoverable} on.
 *
 * @author mcawful
 */
@Entity
@Table(name = "user_guild")
@IdClass(UserGuild.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class UserGuild {
	/**
	 * The user ID.
	 */
	@Id
	@Column(name = "user_id")
	private UUID userId;

	/**
	 * The Discord server ID.
	 */
	@Id
	@Column(name = "guild_id")
	private long guildId;

	/**
	 * The server name.
	 */
	@Column(name = "guild_name", nullable = false)
	private String guildName;

	/**
	 * The server icon hash, or {@code null}.
	 */
	@Column(name = "icon_hash")
	private String iconHash;

	/**
	 * Whether people in this server may find the user. Off until the user opts in.
	 */
	@Column(nullable = false)
	private boolean discoverable;

	/**
	 * When the row was last refreshed from Discord.
	 */
	@Column(name = "synced_at", nullable = false)
	private Instant syncedAt;

	/**
	 * Creates a newly synced, not discoverable row.
	 *
	 * @param userId    the user ID
	 * @param guildId   the Discord server ID
	 * @param guildName the server name
	 * @param iconHash  the icon hash, or {@code null}
	 * @param syncedAt  the sync time
	 */
	public UserGuild(UUID userId, long guildId, String guildName, String iconHash, Instant syncedAt) {
		this.userId = userId;
		this.guildId = guildId;
		this.guildName = guildName;
		this.iconHash = iconHash;
		this.syncedAt = syncedAt;
	}

	/**
	 * The composite primary key.
	 *
	 * @param userId  the user ID
	 * @param guildId the Discord server ID
	 * @author mcawful
	 */
	public record Key(UUID userId, long guildId) implements Serializable {
	}
}
