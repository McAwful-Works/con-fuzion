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

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import app.confuzion.backend.model.record.GrantedRole;

/**
 * Reads and writes {@code user_role}, the granted MODERATOR and ADMIN roles
 * (D-015). {@code USER} is implicit and never stored.
 * <p>
 * Role changes are serialized by two locks, always taken in this order: the
 * target's {@code app_user} row ({@link #lockUser}), then every ADMIN row
 * ({@link #lockAdmins}). The first keeps a role change, a bootstrap grant at
 * sign-in and a suspension or ban of the same user from interleaving; the
 * second makes the "last ADMIN" check and the change it guards atomic, so two
 * admins removing each other at once can't leave none.
 *
 * @author mcawful
 */
@Repository
public class UserRoleRepository {
	/**
	 * The {@link JdbcClient}.
	 */
	private final JdbcClient jdbc;

	/**
	 * Creates the repository.
	 *
	 * @param jdbc the {@link JdbcClient}
	 */
	public UserRoleRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Returns a user's granted roles.
	 *
	 * @param userId the user's ID
	 * @return the roles, MODERATOR before ADMIN; empty for a plain user
	 */
	public List<GrantedRole> findRoles(UUID userId) {
		var roles = EnumSet.noneOf(GrantedRole.class);
		jdbc.sql("SELECT role FROM user_role WHERE user_id = :userId").param("userId", userId)
				.query((rs, row) -> GrantedRole.valueOf(rs.getString("role"))).list().forEach(roles::add);
		return List.copyOf(roles);
	}

	/**
	 * Locks a user's {@code app_user} row until the transaction ends. The lock is
	 * {@code FOR NO KEY UPDATE}, which conflicts with a sign-in's upsert and with
	 * another lock of the same row, but not with the foreign-key checks of rows
	 * that only reference the user.
	 *
	 * @param userId the user's ID
	 * @return whether Discord reported MFA on at the user's last sign-in, or empty
	 *         when there is no such user
	 */
	public Optional<Boolean> lockUser(UUID userId) {
		return jdbc.sql("SELECT mfa_enabled FROM app_user WHERE id = :userId FOR NO KEY UPDATE").param("userId", userId)
				.query(Boolean.class).optional();
	}

	/**
	 * Locks every ADMIN row until the transaction ends and returns who holds them.
	 * A transaction that waited for the lock gets the rows it found, re-checked
	 * after the one before it committed: a row that one deleted is left out. An
	 * ADMIN row inserted meanwhile isn't seen, which can only make the last-ADMIN
	 * check stricter.
	 *
	 * @return the IDs of the users holding ADMIN
	 */
	public List<UUID> lockAdmins() {
		return jdbc.sql("SELECT user_id FROM user_role WHERE role = 'ADMIN' FOR UPDATE").query(UUID.class).list();
	}

	/**
	 * Grants a role.
	 *
	 * @param userId    the user's ID
	 * @param role      the role
	 * @param grantedBy the admin who granted it, or {@code null} for a bootstrap
	 *                  grant
	 * @return {@code true} when the role was granted, {@code false} when the user
	 *         already held it
	 */
	public boolean grant(UUID userId, GrantedRole role, UUID grantedBy) {
		return jdbc.sql("""
				INSERT INTO user_role (user_id, role, granted_by) VALUES (:userId, :role, :grantedBy)
				ON CONFLICT (user_id, role) DO NOTHING""").param("userId", userId).param("role", role.name())
				.param("grantedBy", grantedBy).update() == 1;
	}

	/**
	 * Revokes a role.
	 *
	 * @param userId the user's ID
	 * @param role   the role
	 */
	public void revoke(UUID userId, GrantedRole role) {
		jdbc.sql("DELETE FROM user_role WHERE user_id = :userId AND role = :role").param("userId", userId)
				.param("role", role.name()).update();
	}
}
