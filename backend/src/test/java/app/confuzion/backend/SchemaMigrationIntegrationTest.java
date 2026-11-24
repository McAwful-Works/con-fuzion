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
package app.confuzion.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for the {@code app_user} and {@code user_guild} migration
 * on Testcontainers Postgres. Each test rolls back.
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SchemaMigrationIntegrationTest {
	/**
	 * The {@link JdbcTemplate}.
	 */
	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * Tests that Flyway applied the migration.
	 */
	@Test
	void migrationApplied() {
		assertThat(jdbcTemplate.queryForObject(
				"SELECT success FROM flyway_schema_history WHERE version = '202611241300'", Boolean.class)).isTrue();
	}

	/**
	 * Tests that {@code app_user} has exactly the planned columns, and no email.
	 */
	@Test
	void appUserColumnsHaveNoEmail() {
		assertThat(columns("app_user")).containsExactlyInAnyOrder("id", "discord_id", "username", "global_name",
				"avatar_hash", "mfa_enabled", "time_zone", "push_enabled", "suspended_until", "created_at",
				"last_login_at");
		assertThat(columns("user_guild")).containsExactlyInAnyOrder("user_id", "guild_id", "guild_name", "icon_hash",
				"discoverable", "synced_at");
	}

	/**
	 * Tests the defaults: a generated ID, settings off, and a server that isn't
	 * discoverable until the user opts in.
	 */
	@Test
	void defaultsArePrivate() {
		var userId = insertUser(81384788765712384L);
		jdbcTemplate.update("INSERT INTO user_guild (user_id, guild_id, guild_name) VALUES (?, ?, ?)", userId,
				175928847299117063L, "Example Con Community");

		assertThat(jdbcTemplate.queryForObject("SELECT mfa_enabled OR push_enabled FROM app_user WHERE id = ?",
				Boolean.class, userId)).isFalse();
		assertThat(jdbcTemplate.queryForObject("SELECT discoverable FROM user_guild WHERE user_id = ?", Boolean.class,
				userId)).isFalse();
	}

	/**
	 * Tests that a Discord account maps to one user.
	 */
	@Test
	void discordIdIsUnique() {
		insertUser(81384788765712384L);

		assertThatThrownBy(() -> insertUser(81384788765712384L)).isInstanceOf(DuplicateKeyException.class);
	}

	/**
	 * Tests that deleting a user deletes their servers.
	 */
	@Test
	void deletingUserDeletesTheirGuilds() {
		var userId = insertUser(81384788765712384L);
		jdbcTemplate.update("INSERT INTO user_guild (user_id, guild_id, guild_name) VALUES (?, ?, ?)", userId,
				175928847299117063L, "Example Con Community");

		jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM user_guild", Integer.class)).isZero();
	}

	/**
	 * Tests the indexes: the primary key leads with {@code user_id}, and the guild
	 * index covers discoverable rows only.
	 */
	@Test
	void indexes() {
		assertThat(indexDefinition("user_guild_pkey")).endsWith("(user_id, guild_id)");
		assertThat(indexDefinition("user_guild_discoverable_guild_idx"))
				.endsWith("(guild_id, user_id) WHERE discoverable");
		assertThat(indexDefinition("app_user_discord_id_key")).startsWith("CREATE UNIQUE INDEX")
				.endsWith("(discord_id)");
	}

	/**
	 * Inserts a user with only the required columns.
	 *
	 * @param discordId the Discord ID
	 * @return the generated user ID
	 */
	private UUID insertUser(long discordId) {
		return jdbcTemplate.queryForObject("INSERT INTO app_user (discord_id, username) VALUES (?, ?) RETURNING id",
				UUID.class, discordId, "con_goer");
	}

	/**
	 * Returns a table's column names.
	 *
	 * @param table the table name
	 * @return the column names
	 */
	private List<String> columns(String table) {
		return jdbcTemplate.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = ?",
				String.class, table);
	}

	/**
	 * Returns an index's {@code CREATE INDEX} statement.
	 *
	 * @param index the index name
	 * @return the definition
	 */
	private String indexDefinition(String index) {
		return jdbcTemplate.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = ?", String.class, index);
	}
}
