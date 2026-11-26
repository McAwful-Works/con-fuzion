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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import app.confuzion.backend.model.record.ReportResponse;
import app.confuzion.backend.model.record.ReportResponse.Reason;
import app.confuzion.backend.model.record.ReportResponse.Status;
import app.confuzion.backend.model.record.ReportResponse.TargetType;
import app.confuzion.backend.model.record.ResolveReportRequest.Resolution;

/**
 * Native SQL for reports and the moderation queue (D-015). A report names its
 * target by ID only, with no foreign key, so it outlives the target. Every read
 * and resolve for a moderator leaves out the reports about that moderator or
 * their own events ({@link #NOT_ABOUT_VIEWER}): the reported user never sees a
 * report, whatever their role.
 *
 * @author mcawful
 */
@Repository
public class ReportRepository {
	/**
	 * Whether report {@code r} is about neither the viewer {@code :viewer} nor an
	 * event they host.
	 */
	private static final String NOT_ABOUT_VIEWER = """
			NOT (r.target_type = 'USER' AND r.target_id = :viewer)
			AND NOT (r.target_type = 'EVENT' AND EXISTS (SELECT 1 FROM event h
				WHERE h.public_id = r.target_id AND h.host_id = :viewer))""";

	/**
	 * A report with its target's label: the event's title or the user's display
	 * name, or {@code null} once the target is gone.
	 */
	private static final String SELECT_REPORT = """
			SELECT r.*, CASE WHEN r.target_type = 'EVENT' THEN e.title ELSE coalesce(u.global_name, u.username)
				END AS target_label
			FROM report r
			LEFT JOIN event e ON r.target_type = 'EVENT' AND e.public_id = r.target_id
			LEFT JOIN app_user u ON r.target_type = 'USER' AND u.id = r.target_id
			WHERE\s""" + NOT_ABOUT_VIEWER;

	/**
	 * The {@link JdbcClient}.
	 */
	private final JdbcClient jdbc;

	/**
	 * Creates the repository.
	 *
	 * @param jdbc the {@link JdbcClient}
	 */
	public ReportRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Locks the reporter's {@code app_user} row until the transaction ends, so that
	 * two reports by one user check the duplicate rule and the daily cap one after
	 * the other. {@code NO KEY UPDATE} doesn't conflict with foreign-key checks.
	 *
	 * @param reporterId the reporter's user ID
	 */
	public void lockReporter(UUID reporterId) {
		jdbc.sql("SELECT id FROM app_user WHERE id = :id FOR NO KEY UPDATE").param("id", reporterId).query(UUID.class)
				.single();
	}

	/**
	 * Returns whether a reporter already has an open report on a target.
	 *
	 * @param reporterId the reporter's user ID
	 * @param targetType what the target is
	 * @param targetId   the event's public ID or the user's ID
	 * @return {@code true} when one is open
	 */
	public boolean hasOpen(UUID reporterId, TargetType targetType, UUID targetId) {
		return jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM report WHERE reporter_id = :reporterId AND target_type = :targetType
					AND target_id = :targetId AND status = 'OPEN')""").param("reporterId", reporterId)
				.param("targetType", targetType.name()).param("targetId", targetId).query(Boolean.class).single();
	}

	/**
	 * Counts the reports a user made in the last 24 hours, in any status.
	 *
	 * @param reporterId the reporter's user ID
	 * @return the count
	 */
	public int countCreatedInLastDay(UUID reporterId) {
		return jdbc
				.sql("""
						SELECT count(*) FROM report WHERE reporter_id = :reporterId AND created_at > now() - interval '24 hours'""")
				.param("reporterId", reporterId).query(Integer.class).single();
	}

	/**
	 * Adds an open report.
	 *
	 * @param reporterId the reporter's user ID
	 * @param targetType what the target is
	 * @param targetId   the event's public ID or the user's ID
	 * @param reason     why
	 * @param note       the reporter's note, or {@code null}
	 * @return the new report's ID
	 */
	public long insert(UUID reporterId, TargetType targetType, UUID targetId, Reason reason, String note) {
		return jdbc.sql("""
				INSERT INTO report (reporter_id, target_type, target_id, reason, note)
				VALUES (:reporterId, :targetType, :targetId, :reason, CAST(:note AS varchar))
				RETURNING id""").param("reporterId", reporterId).param("targetType", targetType.name())
				.param("targetId", targetId).param("reason", reason.name()).param("note", note).query(Long.class)
				.single();
	}

	/**
	 * Returns a page of reports in one status: {@code OPEN} oldest first, the
	 * others newest first.
	 *
	 * @param viewer the moderator's user ID
	 * @param status the status
	 * @param after  the last ID of the previous page, or {@code null} for the first
	 * @param limit  the most to return
	 * @return the reports
	 */
	public List<ReportResponse> findPage(UUID viewer, Status status, Long after, int limit) {
		var oldestFirst = status == Status.OPEN;
		return jdbc
				.sql(SELECT_REPORT + " AND r.status = :status AND (CAST(:after AS bigint) IS NULL OR r.id "
						+ (oldestFirst ? ">" : "<") + " :after) ORDER BY r.id " + (oldestFirst ? "ASC" : "DESC")
						+ " LIMIT :limit")
				.param("viewer", viewer).param("status", status.name()).param("after", after).param("limit", limit)
				.query(ReportRepository::report).list();
	}

	/**
	 * Returns one report.
	 *
	 * @param viewer   the moderator's user ID
	 * @param reportId the report's ID
	 * @return the report, or empty when it doesn't exist or is about the viewer
	 */
	public Optional<ReportResponse> find(UUID viewer, long reportId) {
		return jdbc.sql(SELECT_REPORT + " AND r.id = :id").param("viewer", viewer).param("id", reportId)
				.query(ReportRepository::report).optional();
	}

	/**
	 * Resolves an open report, unless it is about the moderator.
	 *
	 * @param viewer     the moderator's user ID
	 * @param reportId   the report's ID
	 * @param resolution how it is closed
	 * @param note       the moderator's note, or {@code null}
	 * @return the resolved report's reporter, or empty when no open report matched
	 */
	public Optional<Resolved> resolve(UUID viewer, long reportId, Resolution resolution, String note) {
		return jdbc.sql("""
				UPDATE report r SET status = :status, handled_by = :viewer, handled_at = now(),
					resolution_note = CAST(:note AS varchar)
				WHERE r.id = :id AND r.status = 'OPEN' AND\s""" + NOT_ABOUT_VIEWER + " RETURNING r.reporter_id")
				.param("status", resolution.name()).param("viewer", viewer).param("note", note).param("id", reportId)
				.query((rs, row) -> new Resolved(rs.getObject("reporter_id", UUID.class))).optional();
	}

	/**
	 * Maps a row of {@link #SELECT_REPORT}.
	 *
	 * @param rs  the row
	 * @param row the row number
	 * @return the {@link ReportResponse}
	 * @throws SQLException when a column can't be read
	 */
	private static ReportResponse report(ResultSet rs, int row) throws SQLException {
		return new ReportResponse(rs.getLong("id"), TargetType.valueOf(rs.getString("target_type")),
				rs.getObject("target_id", UUID.class), rs.getString("target_label"),
				Reason.valueOf(rs.getString("reason")), rs.getString("note"), Status.valueOf(rs.getString("status")),
				rs.getObject("reporter_id", UUID.class), instant(rs, "created_at"),
				rs.getObject("handled_by", UUID.class), instant(rs, "handled_at"), rs.getString("resolution_note"));
	}

	/**
	 * Reads a nullable {@code timestamptz} column.
	 *
	 * @param rs     the row
	 * @param column the column
	 * @return the time, or {@code null}
	 * @throws SQLException when the column can't be read
	 */
	private static Instant instant(ResultSet rs, String column) throws SQLException {
		var time = rs.getObject(column, OffsetDateTime.class);
		return time == null ? null : time.toInstant();
	}

	/**
	 * A resolved report.
	 *
	 * @param reporterId the reporter's user ID, or {@code null} once their account
	 *                   is gone
	 * @author mcawful
	 */
	public record Resolved(UUID reporterId) {
	}
}
