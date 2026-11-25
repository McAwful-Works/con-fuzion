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

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import app.confuzion.backend.model.record.AuditDetails;
import app.confuzion.backend.model.record.AuditEntryResponse.Action;
import app.confuzion.backend.model.record.AuditEntryResponse.TargetType;
import tools.jackson.databind.json.JsonMapper;

/**
 * Native SQL for the insert-only audit log (plan B12, D-015). Every moderator
 * and admin action, refused sign-in, role change and session purge writes its
 * row through {@link #insert}, in the same transaction as the change it
 * records, so a change that rolls back leaves no row and a row never lacks its
 * change. The database refuses every {@code UPDATE}, {@code DELETE} and
 * {@code TRUNCATE}, so there is no other write path.
 *
 * @author mcawful
 */
@Repository
public class AuditLogRepository {
	/**
	 * The {@link JdbcClient}.
	 */
	private final JdbcClient jdbc;

	/**
	 * The {@link JsonMapper}, to store details.
	 */
	private final JsonMapper jsonMapper;

	/**
	 * Creates the repository.
	 *
	 * @param jdbc       the {@link JdbcClient}
	 * @param jsonMapper the {@link JsonMapper}
	 */
	public AuditLogRepository(JdbcClient jdbc, JsonMapper jsonMapper) {
		this.jdbc = jdbc;
		this.jsonMapper = jsonMapper;
	}

	/**
	 * Appends a row to the audit log. The IDs are stored as plain values, with no
	 * foreign keys, so the row outlives the accounts, events and reports it names.
	 *
	 * @param actorId    the moderator or admin who acted, or {@code null} for the
	 *                   system
	 * @param action     what happened
	 * @param targetType what it happened to
	 * @param targetId   the target's ID: a user ID or event public ID, a report ID
	 *                   or a Discord ID, as text
	 * @param details    the action's own fields, with only those set (D-015), or
	 *                   {@code null} for an action that has none
	 * @return the new row's ID
	 */
	public long insert(UUID actorId, Action action, TargetType targetType, String targetId, AuditDetails details) {
		return jdbc.sql("""
				INSERT INTO audit_log (actor_id, action, target_type, target_id, details)
				VALUES (:actorId, :action, :targetType, :targetId, CAST(:details AS jsonb))
				RETURNING id""").param("actorId", actorId).param("action", action.name())
				.param("targetType", targetType.name()).param("targetId", targetId)
				.param("details", details == null ? "{}" : jsonMapper.writeValueAsString(details)).query(Long.class)
				.single();
	}
}
