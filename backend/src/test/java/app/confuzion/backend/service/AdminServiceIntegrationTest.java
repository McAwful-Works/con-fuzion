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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;

import app.confuzion.backend.TestcontainersConfiguration;
import app.confuzion.backend.exception.ConflictException;
import app.confuzion.backend.exception.ConflictException.Conflict;
import app.confuzion.backend.exception.InvalidInputException;
import app.confuzion.backend.model.record.GrantedRole;

/**
 * Integration tests for {@link AdminService}'s role changes against Postgres
 * and Valkey: the locks that keep two admins removing each other from leaving
 * none, the re-check of the actor's own ADMIN, and the cursor checks. The
 * end-to-end paths over HTTP are in {@code SecurityConfigIntegrationTest}.
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AdminServiceIntegrationTest {
	/**
	 * How long a concurrent step may take before the test fails.
	 */
	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	/**
	 * The {@link AdminService}.
	 */
	@Autowired
	private AdminService adminService;

	/**
	 * The {@link JdbcTemplate}.
	 */
	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * The {@link TransactionTemplate}, to hold the ADMIN rows' lock.
	 */
	@Autowired
	private TransactionTemplate transactionTemplate;

	/**
	 * Tests that two admins removing each other's ADMIN at the same moment leave
	 * one admin: both changes wait on the ADMIN rows' lock, and whichever runs
	 * second sees the first's outcome and is refused, because its actor is no
	 * longer an admin or its target would be the last one. Only the change that ran
	 * writes an audit row.
	 */
	@Test
	void twoAdminsRemovingEachOtherLeaveOne() throws Exception {
		jdbcTemplate.update("DELETE FROM user_role WHERE role = 'ADMIN'");
		var first = user();
		var second = user();
		grant(first, GrantedRole.ADMIN);
		grant(second, GrantedRole.ADMIN);
		var locked = new CountDownLatch(1);
		var release = new CountDownLatch(1);

		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var blocker = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
				jdbcTemplate.queryForList("SELECT user_id FROM user_role WHERE role = 'ADMIN' FOR UPDATE");
				locked.countDown();
				await(release);
			}));
			await(locked);
			var changes = List.<Future<?>>of(executor.submit(() -> adminService.setRoles(first, second, Set.of())),
					executor.submit(() -> adminService.setRoles(second, first, Set.of())));
			awaitLockWaiters(2);
			release.countDown();
			blocker.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

			var failures = new ArrayList<Throwable>();
			for (var change : changes) {
				try {
					change.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
				} catch (ExecutionException e) {
					failures.add(e.getCause());
				}
			}
			assertThat(failures).singleElement().satisfiesAnyOf(
					failure -> assertThat(failure).isInstanceOf(AccessDeniedException.class),
					failure -> assertThat(failure).isInstanceOfSatisfying(ConflictException.class,
							conflict -> assertThat(conflict.getConflict()).isEqualTo(Conflict.LAST_ADMIN)));
		}
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM user_role WHERE role = 'ADMIN'", Long.class))
				.isOne();
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE action = 'ROLES_CHANGED' AND target_id IN (?, ?)", Long.class,
				first.toString(), second.toString())).isOne();
	}

	/**
	 * Tests that an actor who no longer holds ADMIN can't change roles, even though
	 * their session still says ADMIN, and that nothing is written.
	 */
	@Test
	void actorWithoutAdminIsRefused() {
		var actor = user();
		var target = user();

		assertThatThrownBy(() -> adminService.setRoles(actor, target, Set.of(GrantedRole.MODERATOR)))
				.isInstanceOf(AccessDeniedException.class);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM user_role WHERE user_id = ?", Long.class, target))
				.isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE target_id = ?", Long.class,
				target.toString())).isZero();
	}

	/**
	 * Tests that a grant records who granted it, and that an unknown user is a
	 * not-found for every roles operation.
	 */
	@Test
	void grantRecordsTheAdminAndUnknownUsersAreNotFound() {
		var admin = user();
		grant(admin, GrantedRole.ADMIN);
		var target = user();

		assertThat(adminService.setRoles(admin, target, Set.of(GrantedRole.ADMIN, GrantedRole.MODERATOR)).roles())
				.containsExactly(GrantedRole.MODERATOR, GrantedRole.ADMIN);
		assertThat(jdbcTemplate.queryForList("SELECT granted_by FROM user_role WHERE user_id = ?", UUID.class, target))
				.containsExactly(admin, admin);
		assertThat(adminService.getRoles(target).roles()).containsExactly(GrantedRole.MODERATOR, GrantedRole.ADMIN);

		var unknown = UUID.randomUUID();
		assertThatThrownBy(() -> adminService.getRoles(unknown)).isInstanceOf(NoSuchElementException.class);
		assertThatThrownBy(() -> adminService.setRoles(admin, unknown, Set.of()))
				.isInstanceOf(NoSuchElementException.class);
	}

	/**
	 * Tests that a cursor naming no valid row ID is a bad request.
	 */
	@Test
	void badCursorsAreInvalidInput() {
		for (var cursor : List.of("AAAA", "MA", "LTE"))
			assertThatThrownBy(() -> adminService.getAudit(cursor, 20)).as(cursor)
					.isInstanceOf(InvalidInputException.class);
	}

	/**
	 * Creates a user with MFA on.
	 *
	 * @return the user ID
	 */
	private UUID user() {
		return jdbcTemplate.queryForObject(
				"INSERT INTO app_user (discord_id, username, mfa_enabled) VALUES (?, 'admin_test', true) RETURNING id",
				UUID.class, ThreadLocalRandom.current().nextLong(1L << 40, Long.MAX_VALUE));
	}

	/**
	 * Grants a role directly.
	 *
	 * @param userId the user ID
	 * @param role   the role
	 */
	private void grant(UUID userId, GrantedRole role) {
		jdbcTemplate.update("INSERT INTO user_role (user_id, role) VALUES (?, ?)", userId, role.name());
	}

	/**
	 * Waits until this many other database sessions are waiting for a lock.
	 *
	 * @param waiters the number of waiters
	 */
	private void awaitLockWaiters(int waiters) throws InterruptedException {
		var deadline = Instant.now().plus(TIMEOUT);
		while (jdbcTemplate.queryForObject("""
				SELECT count(*) FROM pg_stat_activity
				WHERE datname = current_database() AND wait_event_type = 'Lock'""", Long.class) < waiters) {
			assertThat(Instant.now()).as("lock waiters").isBefore(deadline);
			Thread.sleep(20);
		}
	}

	/**
	 * Waits for a latch, failing the test after {@link #TIMEOUT}.
	 *
	 * @param latch the latch
	 */
	private static void await(CountDownLatch latch) {
		try {
			assertThat(latch.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}
}
