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
package app.confuzion.backend.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Unit tests for the {@link HttpUtil} class.
 *
 * @author mcawful
 */
class HttpUtilTest {
	/**
	 * Tests that the {@code getRemoteAddr} method ignores client-supplied
	 * forwarding headers and returns the request's remote address.
	 *
	 * @param header a header that older code trusted for the client IP
	 */
	@ParameterizedTest
	@ValueSource(strings = { "X-Forwarded-For", "Proxy-Client-IP", "WL-Proxy-Client-IP", "HTTP_CLIENT_IP",
			"HTTP_X_FORWARDED_FOR", "Forwarded" })
	void testGetRemoteAddrIgnoresForwardingHeaders(String header) {
		var mockRequest = new MockHttpServletRequest();
		mockRequest.setRemoteAddr("198.51.100.1");
		mockRequest.addHeader(header, "203.0.113.66");

		assertThat(HttpUtil.getRemoteAddr(mockRequest)).isEqualTo("198.51.100.1");
	}

	/**
	 * Tests that the {@code getUserId} method returns the principal name.
	 */
	@Test
	void testGetUserIdWithPrincipal() {
		var mockRequest = new MockHttpServletRequest();
		mockRequest.setUserPrincipal(() -> "1234567890");

		assertThat(HttpUtil.getUserId(mockRequest)).contains("1234567890");
	}

	/**
	 * Tests that the {@code getUserId} method returns empty for an anonymous
	 * request.
	 */
	@Test
	void testGetUserIdAnonymous() {
		assertThat(HttpUtil.getUserId(new MockHttpServletRequest())).isEmpty();
	}
}
