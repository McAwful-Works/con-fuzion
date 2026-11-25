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
package app.confuzion.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import app.confuzion.backend.TestcontainersConfiguration;
import app.confuzion.backend.model.record.AuditDetails;
import app.confuzion.backend.model.record.AuditEntryResponse;
import app.confuzion.backend.model.record.AuditEntryResponse.Action;
import app.confuzion.backend.model.record.AuditEntryResponse.TargetType;
import app.confuzion.backend.model.record.GrantedRole;
import app.confuzion.backend.model.record.ResolveReportRequest.Resolution;

/**
 * Tests {@link AuditLogRepository#insert} against Postgres: the row it writes,
 * details with only the action's own fields, a system row with no actor, and
 * that the row can't be changed afterwards.
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AuditLogRepositoryIntegrationTest {
	/**
	 * The {@link AuditLogRepository}.
	 */
	@Autowired
	private AuditLogRepository auditLogRepository;

	/**
	 * The {@link JdbcTemplate}.
	 */
	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * Tests that a moderator's action is stored with its actor, target and only the
	 * details that were set, times as ISO-8601 strings, and the time it was
	 * written.
	 */
	@Test
	void insertStoresTheActionWithOnlyItsDetails() {
		var actor = UUID.randomUUID();
		var target = UUID.randomUUID();
		var until = Instant.parse("2026-12-24T18:00:00Z");

		var id = auditLogRepository.insert(actor, Action.USER_SUSPENDED, TargetType.USER, target.toString(),
				new AuditDetails("Harassment at the meetup", until, null, null, null, null, null));

		var row = jdbcTemplate.queryForMap("SELECT actor_id, action, target_type, target_id, details::text AS details, "
				+ "created_at > now() - interval '1 minute' AS recent FROM audit_log WHERE id = ?", id);
		assertThat(row).containsEntry("actor_id", actor).containsEntry("action", "USER_SUSPENDED")
				.containsEntry("target_type", "USER").containsEntry("target_id", target.toString())
				.containsEntry("recent", true);
		assertThat((String) row.get("details"))
				.isEqualTo("{\"until\": \"2026-12-24T18:00:00Z\", \"reason\": \"Harassment at the meetup\"}");
	}

	/**
	 * Tests that a system row has no actor, that enum and list details are stored
	 * by name, that no details at all are an empty object, and that IDs increase.
	 */
	@Test
	void insertStoresASystemRowAndEnumDetails() {
		var first = auditLogRepository.insert(null, Action.ROLES_CHANGED, TargetType.USER, UUID.randomUUID().toString(),
				new AuditDetails(null, null, null, null, List.of(GrantedRole.ADMIN), null, null));
		var second = auditLogRepository.insert(null, Action.SESSIONS_PURGED, TargetType.USER,
				UUID.randomUUID().toString(), new AuditDetails(null, null, null, null, null, Action.ROLES_CHANGED, 2));
		var third = auditLogRepository.insert(UUID.randomUUID(), Action.REPORT_RESOLVED, TargetType.REPORT, "42",
				new AuditDetails(null, null, Resolution.DISMISSED, null, null, null, null));
		var fourth = auditLogRepository.insert(null, Action.EVENT_UNHIDDEN, TargetType.EVENT,
				UUID.randomUUID().toString(), new AuditDetails(null, null, null, null, null, null, null));
		var fifth = auditLogRepository.insert(UUID.randomUUID(), Action.ACCOUNT_UNBANNED, TargetType.DISCORD_ACCOUNT,
				"81384788765712384", null);

		assertThat(List.of(first, second, third, fourth, fifth)).isSorted().doesNotHaveDuplicates();
		assertThat(jdbcTemplate.queryForObject("SELECT actor_id FROM audit_log WHERE id = ?", UUID.class, first))
				.isNull();
		assertThat(details(first)).isEqualTo("{\"roles\": [\"ADMIN\"]}");
		assertThat(details(second)).isEqualTo("{\"cause\": \"ROLES_CHANGED\", \"sessions\": 2}");
		assertThat(details(third)).isEqualTo("{\"resolution\": \"DISMISSED\"}");
		assertThat(details(fourth)).isEqualTo("{}");
		assertThat(details(fifth)).isEqualTo("{}");
	}

	/**
	 * Tests that a Discord ID target is stored as text, and that the row it wrote
	 * can't be changed or removed.
	 */
	@Test
	void insertedRowCantBeChanged() {
		var id = auditLogRepository.insert(null, Action.SIGN_IN_REFUSED, TargetType.DISCORD_ACCOUNT,
				"81384788765712384", new AuditDetails(null, null, null, null, null, Action.ACCOUNT_BANNED, null));

		assertThat(jdbcTemplate.queryForObject("SELECT target_id FROM audit_log WHERE id = ?", String.class, id))
				.isEqualTo("81384788765712384");
		assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM audit_log WHERE id = ?", id)).rootCause()
				.hasMessageContaining("audit_log is insert-only");
	}

	/**
	 * Tests that every statement that would change or remove rows fails, even one
	 * that changes nothing visible. Each runs in its own (rolled back) transaction,
	 * since the failure aborts it.
	 *
	 * @param statement the SQL, with the row ID as its one parameter where it takes
	 *                  one
	 */
	@ParameterizedTest
	@ValueSource(strings = { "UPDATE audit_log SET details = '{}' WHERE id = ?",
			"UPDATE audit_log SET actor_id = NULL WHERE id = ?", "DELETE FROM audit_log WHERE id >= ?",
			"TRUNCATE audit_log" })
	void rowsCantBeUpdatedDeletedOrTruncated(String statement) {
		var id = auditLogRepository.insert(UUID.randomUUID(), Action.EVENT_UNHIDDEN, TargetType.EVENT,
				UUID.randomUUID().toString(), null);
		var args = statement.contains("?") ? new Object[] { id } : new Object[0];

		assertThatThrownBy(() -> jdbcTemplate.update(statement, args)).rootCause()
				.hasMessageContaining("audit_log is insert-only");
	}

	/**
	 * Tests that a page is newest first, starts after the given ID, and reads the
	 * details back as written.
	 */
	@Test
	void findPageIsNewestFirst() {
		var actor = UUID.randomUUID();
		var first = auditLogRepository.insert(actor, Action.EVENT_HIDDEN, TargetType.EVENT,
				UUID.randomUUID().toString(), new AuditDetails("Unsafe", null, null, null, null, null, null));
		var second = auditLogRepository.insert(null, Action.SIGN_IN_REFUSED, TargetType.DISCORD_ACCOUNT,
				"81384788765712384", new AuditDetails(null, null, null, null, null, Action.USER_SUSPENDED, null));
		var third = auditLogRepository.insert(actor, Action.EVENT_UNHIDDEN, TargetType.EVENT,
				UUID.randomUUID().toString(), null);

		assertThat(auditLogRepository.findPage(null, 2)).extracting(AuditEntryResponse::id).containsExactly(third,
				second);
		var older = auditLogRepository.findPage(third, 2);
		assertThat(older).extracting(AuditEntryResponse::id).containsExactly(second, first);
		assertThat(older.get(0).actorId()).isNull();
		assertThat(older.get(0).targetId()).isEqualTo("81384788765712384");
		assertThat(older.get(0).details().cause()).isEqualTo(Action.USER_SUSPENDED);
		assertThat(older.get(1).actorId()).isEqualTo(actor);
		assertThat(older.get(1).action()).isEqualTo(Action.EVENT_HIDDEN);
		assertThat(older.get(1).details().reason()).isEqualTo("Unsafe");
		assertThat(older.get(1).createdAt()).isNotNull();
	}

	/**
	 * Returns a row's details as Postgres prints them.
	 *
	 * @param id the row's ID
	 * @return the {@code jsonb} text
	 */
	private String details(long id) {
		return jdbcTemplate.queryForObject("SELECT details::text FROM audit_log WHERE id = ?", String.class, id);
	}
}
