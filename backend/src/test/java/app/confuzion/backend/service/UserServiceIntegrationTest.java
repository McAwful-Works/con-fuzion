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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import app.confuzion.backend.TestcontainersConfiguration;
import app.confuzion.backend.model.record.DiscordGuild;
import app.confuzion.backend.model.record.DiscordUser;

/**
 * Tests the login upsert and guild sync in {@link UserService} against
 * Postgres: inserts, updates and deletes, the kept {@code discoverable} choice,
 * and keeping the last known servers when Discord returned none.
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UserServiceIntegrationTest {
	/**
	 * The {@link UserService}.
	 */
	@Autowired
	private UserService userService;

	/**
	 * The {@link JdbcTemplate}.
	 */
	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * Tests that the first login creates the user and later ones refresh the
	 * profile without creating another row.
	 */
	@Test
	void firstLoginCreatesUserAndLaterLoginsUpdateIt() {
		var first = userService.recordLogin(user("500000000000000001", "before", true), List.of());
		var again = userService.recordLogin(new DiscordUser("500000000000000001", "after", "After", "a_hash", null),
				List.of());

		assertThat(first.firstLogin()).isTrue();
		assertThat(again.firstLogin()).isFalse();
		assertThat(again.userId()).isEqualTo(first.userId());
		var row = jdbcTemplate.queryForMap(
				"SELECT username, global_name, avatar_hash, mfa_enabled FROM app_user WHERE id = ?", first.userId());
		assertThat(row).containsEntry("username", "after").containsEntry("global_name", "After")
				.containsEntry("avatar_hash", "a_hash").containsEntry("mfa_enabled", false);
	}

	/**
	 * Tests the sync: new servers are inserted not discoverable, known ones get
	 * their new name and icon and keep the user's choice, and left ones are
	 * deleted.
	 */
	@Test
	void syncInsertsUpdatesAndDeletes() {
		var userId = userService
				.recordLogin(user("500000000000000002", "syncer", false),
						List.of(guild("600000000000000001", "Kept", "old"), guild("600000000000000002", "Left", null)))
				.userId();
		assertThat(guilds(userId)).containsOnlyKeys(600000000000000001L, 600000000000000002L);
		assertThat(guilds(userId).values()).allSatisfy(row -> assertThat(row.get("discoverable")).isEqualTo(false));
		jdbcTemplate.update("UPDATE user_guild SET discoverable = true WHERE user_id = ? AND guild_id = ?", userId,
				600000000000000001L);

		userService.recordLogin(user("500000000000000002", "syncer", false),
				List.of(guild("600000000000000001", "Renamed", "new"), guild("600000000000000003", "Joined", null)));

		var guilds = guilds(userId);
		assertThat(guilds).containsOnlyKeys(600000000000000001L, 600000000000000003L);
		assertThat(guilds.get(600000000000000001L)).containsEntry("guild_name", "Renamed")
				.containsEntry("icon_hash", "new").containsEntry("discoverable", true);
		assertThat(guilds.get(600000000000000003L)).containsEntry("guild_name", "Joined").containsEntry("discoverable",
				false);
	}

	/**
	 * Tests that a login without a server list keeps the last known one, and an
	 * empty list removes them all.
	 */
	@Test
	void missingGuildListKeepsLastKnown() {
		var userId = userService.recordLogin(user("500000000000000003", "keeper", false),
				List.of(guild("600000000000000004", "Known", null))).userId();

		userService.recordLogin(user("500000000000000003", "keeper", false), null);
		assertThat(guilds(userId)).containsOnlyKeys(600000000000000004L);

		userService.recordLogin(user("500000000000000003", "keeper", false), List.of());
		assertThat(guilds(userId)).isEmpty();
	}

	/**
	 * Builds a Discord user.
	 *
	 * @param id       the Discord ID
	 * @param username the username
	 * @param mfa      the MFA flag
	 * @return the {@link DiscordUser}
	 */
	private static DiscordUser user(String id, String username, boolean mfa) {
		return new DiscordUser(id, username, null, null, mfa);
	}

	/**
	 * Builds a Discord guild.
	 *
	 * @param id   the ID
	 * @param name the name
	 * @param icon the icon hash
	 * @return the {@link DiscordGuild}
	 */
	private static DiscordGuild guild(String id, String name, String icon) {
		return new DiscordGuild(id, name, icon);
	}

	/**
	 * Returns a user's server rows by server ID.
	 *
	 * @param userId the user ID
	 * @return the rows
	 */
	private Map<Long, Map<String, Object>> guilds(UUID userId) {
		var rows = new java.util.HashMap<Long, Map<String, Object>>();
		jdbcTemplate.queryForList("SELECT * FROM user_guild WHERE user_id = ?", userId)
				.forEach(row -> rows.put((Long) row.get("guild_id"), row));
		return rows;
	}
}
