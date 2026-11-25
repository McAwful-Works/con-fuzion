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

import static app.confuzion.backend.controller.ErrorEnvelopeAssert.assertError;
import static app.confuzion.backend.controller.ErrorEnvelopeAssert.json;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import app.confuzion.backend.model.record.AuditPageResponse;
import app.confuzion.backend.model.record.GrantedRole;
import app.confuzion.backend.model.record.RolesResponse;
import app.confuzion.backend.service.AdminService;

/**
 * Unit tests for {@link AdminController}: valid requests reach the service, and
 * bad ones are 400s.
 *
 * @author mcawful
 */
class AdminControllerTest {
	/**
	 * An admin.
	 */
	private static final Authentication AUTHENTICATION = UsernamePasswordAuthenticationToken.authenticated(
			UUID.randomUUID().toString(), null,
			List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN")));

	/**
	 * A user's roles path.
	 */
	private static final String ROLES = "/api/admin/users/3f2c9a4e-6d1b-4f7a-9c3e-2b8d5e1a7f60/roles";

	/**
	 * The user in {@link #ROLES}.
	 */
	private static final UUID USER = UUID.fromString("3f2c9a4e-6d1b-4f7a-9c3e-2b8d5e1a7f60");

	/**
	 * The mocked {@link AdminService}.
	 */

	/**
	 * The {@link MockMvc} for the controller.
	 */
	private final AdminService adminService = mock(AdminService.class);

	/**
	 * The {@link MockMvc} for the controller.
	 */
	private final MockMvc mockMvc = ErrorEnvelopeAssert.mockMvc(new AdminController(adminService));

	/**
	 * Tests that each operation passes the caller, the user and the request on to
	 * the service and wraps its answer.
	 */
	@Test
	void testDelegatesToTheService() throws Exception {
		var actor = UUID.fromString(AUTHENTICATION.getName());
		var roles = new RolesResponse(List.of(GrantedRole.MODERATOR), true);
		when(adminService.getRoles(USER)).thenReturn(roles);
		when(adminService.setRoles(eq(actor), eq(USER), any())).thenReturn(roles);
		when(adminService.getAudit(any(), anyInt())).thenReturn(new AuditPageResponse(List.of(), null));

		mockMvc.perform(get(ROLES).principal(AUTHENTICATION)).andExpect(status().isOk())
				.andExpect(jsonPath("$.responseType").value("ROLES"))
				.andExpect(jsonPath("$.body.roles[0]").value("MODERATOR"));
		mockMvc.perform(json(put(ROLES), "{\"roles\":[\"ADMIN\",\"MODERATOR\"]}").principal(AUTHENTICATION))
				.andExpect(status().isOk()).andExpect(jsonPath("$.body.mfaEnabled").value(true));
		verify(adminService).setRoles(actor, USER, Set.of(GrantedRole.MODERATOR, GrantedRole.ADMIN));
		mockMvc.perform(delete(ROLES).principal(AUTHENTICATION)).andExpect(status().isNoContent());
		verify(adminService).setRoles(actor, USER, Set.of());
		mockMvc.perform(get("/api/admin/audit?cursor=abc_1-X&limit=50").principal(AUTHENTICATION))
				.andExpect(status().isOk()).andExpect(jsonPath("$.responseType").value("AUDIT_PAGE"));
		verify(adminService).getAudit("abc_1-X", 50);
		mockMvc.perform(get("/api/admin/audit").principal(AUTHENTICATION)).andExpect(status().isOk());
		verify(adminService).getAudit(null, 20);
	}

	/**
	 * Returns requests with a bad user ID, role set, cursor or limit. {@code USER}
	 * is implicit, so it can't be granted.
	 *
	 * @return the requests
	 */
	static Stream<MockHttpServletRequestBuilder> badRequests() {
		return Stream.of(get("/api/admin/users/not-a-uuid/roles"), json(put(ROLES), "{}"),
				json(put(ROLES), "{\"roles\":null}"), json(put(ROLES), "{\"roles\":[\"USER\"]}"),
				json(put(ROLES), "{\"roles\":[null]}"), json(put(ROLES), "{\"roles\":\"ADMIN\"}"), put(ROLES),
				get("/api/admin/audit?limit=0"), get("/api/admin/audit?limit=51"),
				get("/api/admin/audit?cursor=" + "a".repeat(201)));
	}

	/**
	 * Tests that a bad request is a 400.
	 *
	 * @param request the request
	 */
	@ParameterizedTest
	@MethodSource("badRequests")
	void testRejectsBadRequests(MockHttpServletRequestBuilder request) throws Exception {
		assertError(mockMvc, request.principal(AUTHENTICATION), HttpStatus.BAD_REQUEST);
	}
}
