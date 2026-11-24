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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import app.confuzion.backend.model.record.PersonListResponse;
import app.confuzion.backend.service.FriendService;

/**
 * Unit tests for {@link PeopleController}: a valid query reaches the service
 * for the session's user, and a bad one is a 400.
 *
 * @author mcawful
 */
class PeopleControllerTest {
	/**
	 * The mocked {@link FriendService}.
	 */
	private final FriendService friendService = mock(FriendService.class);

	/**
	 * The {@link MockMvc}.
	 */
	private final MockMvc mockMvc = ErrorEnvelopeAssert.mockMvc(new PeopleController(friendService));

	/**
	 * Tests that queries at both ends of the length limit reach the service, for
	 * the session's user.
	 */
	@Test
	void testSearchReachesServiceForSessionUser() throws Exception {
		var me = UUID.randomUUID();
		when(friendService.searchPeople(eq(me), anyString())).thenReturn(new PersonListResponse(List.of()));
		for (var q : new String[] { "ab", "a".repeat(PeopleController.MAX_QUERY) })
			mockMvc.perform(
					get("/api/people").param("q", q).principal(new TestingAuthenticationToken(me.toString(), null)))
					.andExpect(status().isOk()).andExpect(jsonPath("$.responseType").value("PERSON_LIST"));
	}

	/**
	 * Tests that a query that is too short, too long, blank or untrimmed is a 400.
	 *
	 * @param q the bad query
	 */
	@ParameterizedTest
	@ValueSource(strings = { "", "a", "  ", " ab", "ab ", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" })
	void testSearchRejectsBadQuery(String q) throws Exception {
		assertError(mockMvc, get("/api/people").param("q", q), HttpStatus.BAD_REQUEST);
	}

	/**
	 * Tests that the query is required.
	 */
	@Test
	void testSearchRequiresQuery() throws Exception {
		assertError(mockMvc, get("/api/people"), HttpStatus.BAD_REQUEST);
	}
}
