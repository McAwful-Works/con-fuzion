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
package app.confuzion.backend.controller;

import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.confuzion.backend.model.record.AuditPageResponse;
import app.confuzion.backend.model.record.ResponseRecord;
import app.confuzion.backend.model.record.RolesRequest;
import app.confuzion.backend.model.record.RolesResponse;
import app.confuzion.backend.service.AdminService;
import app.confuzion.backend.util.HttpUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The {@link RestController} for role grants and the audit log (D-015). Every
 * route needs ADMIN in effect, which {@code SecurityConfig} checks.
 *
 * @author mcawful
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "admin", description = "Role grants and the audit log (ADMIN)")
public class AdminController {
	/**
	 * The {@link AdminService}.
	 */
	private final AdminService adminService;

	/**
	 * Creates the controller.
	 *
	 * @param adminService the {@link AdminService}
	 */
	public AdminController(AdminService adminService) {
		this.adminService = adminService;
	}

	/**
	 * Returns a user's granted roles.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param userId         the user's ID
	 * @return the {@link RolesResponse} envelope
	 */
	@GetMapping("/users/{userId}/roles")
	@Operation(summary = "Get a user's roles (ADMIN)", description = "The granted roles, whether or not MFA puts "
			+ "them in effect.")
	@ApiResponse(responseCode = "200", description = "OK")
	@ApiResponse(responseCode = "404", description = "No such user.")
	public ResponseRecord<RolesResponse> getRoles(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The user's ID") @PathVariable UUID userId) {
		return new ResponseRecord<>(adminService.getRoles(userId));
	}

	/**
	 * Sets a user's granted roles.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param userId         the user's ID
	 * @param request        the {@link RolesRequest}
	 * @return the updated {@link RolesResponse} envelope
	 */
	@PutMapping("/users/{userId}/roles")
	@Operation(summary = "Set a user's roles (ADMIN)", description = "Replaces the user's granted roles with "
			+ "exactly the set sent. If that changes anything, their sessions are deleted, so the new roles apply "
			+ "when they next sign in. The same set again is a 200 that changes nothing. Audit-logged, as is the "
			+ "session purge.")
	@ApiResponse(responseCode = "200", description = "OK")
	@ApiResponse(responseCode = "404", description = "No such user.")
	@ApiResponse(responseCode = "409", description = "No user would hold ADMIN any more (errorType LAST_ADMIN).")
	public ResponseRecord<RolesResponse> setRoles(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The user's ID") @PathVariable UUID userId,
			@Valid @RequestBody RolesRequest request) {
		return new ResponseRecord<>(adminService.setRoles(HttpUtil.userId(authentication), userId, request.roles()));
	}

	/**
	 * Removes all of a user's granted roles.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param userId         the user's ID
	 */
	@DeleteMapping("/users/{userId}/roles")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Remove all of a user's roles (ADMIN)", description = "The same as setting an empty set. A "
			+ "user with no role is a 204 that changes nothing.")
	@ApiResponse(responseCode = "204", description = "Removed, or had none.")
	@ApiResponse(responseCode = "404", description = "No such user.")
	@ApiResponse(responseCode = "409", description = "No user would hold ADMIN any more (errorType LAST_ADMIN).")
	public void removeRoles(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The user's ID") @PathVariable UUID userId) {
		adminService.setRoles(HttpUtil.userId(authentication), userId, Set.of());
	}

	/**
	 * Returns a page of the audit log, newest first.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param cursor         the {@code nextCursor} from the previous page, or
	 *                       {@code null} for the first page
	 * @param limit          the most rows to return
	 * @return the {@link AuditPageResponse} envelope
	 */
	@GetMapping("/audit")
	@Operation(summary = "Read the audit log (ADMIN)", description = "Newest first. Every moderator and admin "
			+ "action, refused sign-in, role change and session purge. Nothing is ever changed or removed.")
	@ApiResponse(responseCode = "200", description = "OK")
	@ApiResponse(responseCode = "400", description = "A cursor that this endpoint didn't issue, or a limit out of range.")
	public ResponseRecord<AuditPageResponse> getAudit(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The previous page's nextCursor. Opaque.") @RequestParam(required = false) @Size(max = 200) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String cursor,
			@Parameter(description = "Rows per page, 1 to 50") @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
		return new ResponseRecord<>(adminService.getAudit(cursor, limit));
	}
}
