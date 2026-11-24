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

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import app.confuzion.backend.model.entity.EventAudience;

/**
 * The {@link EventAudience} repository.
 *
 * @author mcawful
 */
public interface EventAudienceRepository extends JpaRepository<EventAudience, Long> {
	/**
	 * Returns an event's entries, in the order they were added.
	 *
	 * @param eventId the event's internal ID
	 * @return the entries
	 */
	List<EventAudience> findByEventIdOrderById(long eventId);

	/**
	 * Returns the entries of several events as their host sees them, with labels. A
	 * server's label is its name only while the host is still discoverable there,
	 * because otherwise the entry reaches no one.
	 *
	 * @param eventIds the events' internal IDs
	 * @return the entries, grouped by event in the order they were added
	 */
	@Query(value = """
			SELECT a.event_id AS eventId, a.type AS type, a.guild_id AS guildId, c.public_id AS circleId,
				a.user_id AS userId,
				CASE a.type WHEN 'GUILD' THEN g.guild_name WHEN 'CIRCLE' THEN c.name
					WHEN 'USER' THEN coalesce(u.global_name, u.username) END AS label
			FROM event_audience a
			JOIN event e ON e.id = a.event_id
			LEFT JOIN user_guild g ON g.guild_id = a.guild_id AND g.user_id = e.host_id AND g.discoverable
			LEFT JOIN circle c ON c.id = a.circle_id
			LEFT JOIN app_user u ON u.id = a.user_id
			WHERE a.event_id IN (:eventIds)
			ORDER BY a.event_id, a.id
			""", nativeQuery = true)
	List<LabelledEntry> findLabelled(@Param("eventIds") Collection<Long> eventIds);

	/**
	 * An audience entry as its host sees it.
	 *
	 * @author mcawful
	 */
	interface LabelledEntry {
		/**
		 * Returns the event's internal ID.
		 *
		 * @return the event ID
		 */
		Long getEventId();

		/**
		 * Returns the kind of entry.
		 *
		 * @return the type name
		 */
		String getType();

		/**
		 * Returns the Discord server ID, for a server entry.
		 *
		 * @return the server ID, or {@code null}
		 */
		Long getGuildId();

		/**
		 * Returns the circle's public ID, for a circle entry.
		 *
		 * @return the circle's public ID, or {@code null}
		 */
		UUID getCircleId();

		/**
		 * Returns the person's user ID, for a person entry.
		 *
		 * @return the user ID, or {@code null}
		 */
		UUID getUserId();

		/**
		 * Returns the server, circle or person name.
		 *
		 * @return the label, or {@code null}
		 */
		String getLabel();
	}
}
