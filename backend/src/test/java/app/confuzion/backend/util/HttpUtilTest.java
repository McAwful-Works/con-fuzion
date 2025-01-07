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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Unit tests for the {@link HttpUtil} class.
 *
 * @author mcawful
 */
@SpringBootTest
class HttpUtilTest {
	/**
	 * The {@link List} of header names.
	 */
	private static final List<String> HEADER_NAME_LIST = new LinkedList<>();

	/**
	 * The {@link Map} of header names with their values.
	 */
	private static final Map<String, String> HEADER_NAME_VALUE_MAP = new HashMap<>();

	/**
	 * The known IP address header string.
	 */
	private static final String KNOWN_ADDR_HEADER = "X-Forwarded-For";

	/**
	 * Initializes required test data.
	 */
	@BeforeAll
	static void setup() {
		HEADER_NAME_LIST.addAll(Arrays.asList("host", "connection", "accept", "user-agent", "dnt", "referer",
				"accept-encoding", "accept-language"));

		HEADER_NAME_VALUE_MAP.put(HEADER_NAME_LIST.get(0), "localhost:8080");
		HEADER_NAME_VALUE_MAP.put(HEADER_NAME_LIST.get(1), "keep-alive");
		HEADER_NAME_VALUE_MAP.put(HEADER_NAME_LIST.get(2), "text/plain");
		HEADER_NAME_VALUE_MAP.put(HEADER_NAME_LIST.get(3), "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
				+ "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36");
		HEADER_NAME_VALUE_MAP.put(HEADER_NAME_LIST.get(4), "1");
		HEADER_NAME_VALUE_MAP.put(HEADER_NAME_LIST.get(5), "http://localhost:8080/api/v1/swagger-ui/index.html");
		HEADER_NAME_VALUE_MAP.put(HEADER_NAME_LIST.get(6), "gzip, deflate");
		HEADER_NAME_VALUE_MAP.put(HEADER_NAME_LIST.get(7), "en-US,en;q=0.9");
	}

	/**
	 * Tests that the {@code getRequestHeaders} method of the {@link HttpUtil} class
	 * returns the expected response given the headers. Asserts that the actual
	 * response equals the expected response.
	 */
	@Test
	void getRequestHeadersTest_shouldReturnGivenHeaders() {
		var mockRequest = new MockHttpServletRequest();
		var sb = new StringBuilder("[");
		HEADER_NAME_LIST.forEach(ele -> {
			mockRequest.addHeader(ele, HEADER_NAME_VALUE_MAP.get(ele));
			sb.append(ele).append(":\"").append(HEADER_NAME_VALUE_MAP.get(ele)).append("\", ");
		});
		sb.delete(sb.length() - 2, sb.length());
		sb.append("]");

		var response = HttpUtil.getRequestHeaders(mockRequest);

		assertEquals(sb.toString(), response);
	}

	/**
	 * Tests that the {@code getRemoteAddr} method of the {@link HttpUtil} class
	 * returns the expected response given the IP address with a known header key.
	 * Asserts that the actual response equals the expected response.
	 */
	@Test
	void getRemoteAddrTest_shouldReturnGivenHeaders() {
		var addrStr = "127.0.0.1";
		var mockRequest = new MockHttpServletRequest();
		mockRequest.addHeader(KNOWN_ADDR_HEADER, addrStr);

		var response = HttpUtil.getRemoteAddr(mockRequest);

		assertEquals(addrStr, response);
	}

	/**
	 * Tests that the {@code getRemoteAddr} method of the {@link HttpUtil} class
	 * returns the expected response given an empty IP address with a known header
	 * key. Asserts that the actual response equals the expected response.
	 */
	@Test
	void getRemoteAddrTest_shouldReturnEmpty() {
		var addrStr = "";
		var mockRequest = new MockHttpServletRequest();
		mockRequest.setRemoteAddr(addrStr);
		mockRequest.addHeader(KNOWN_ADDR_HEADER, addrStr);

		var response = HttpUtil.getRemoteAddr(mockRequest);

		assertEquals(addrStr, response);
	}

	/**
	 * Tests that the {@code getRemoteAddr} method of the {@link HttpUtil} class
	 * returns the expected response given an IP address with a value of
	 * {@code unknown} with a known header key. Asserts that the actual response
	 * equals the expected response.
	 */
	@Test
	void getRemoteAddrTest_shouldReturnUnknown() {
		var addrStr = "unknown";
		var mockRequest = new MockHttpServletRequest();
		mockRequest.setRemoteAddr(addrStr);
		mockRequest.addHeader(KNOWN_ADDR_HEADER, addrStr);

		var response = HttpUtil.getRemoteAddr(mockRequest);

		assertEquals(addrStr, response);
	}

	/**
	 * Tests that the {@code getRemoteAddr} method of the {@link HttpUtil} class
	 * returns the expected response given the IP address with an unknown header
	 * key. Asserts that the actual response equals the expected response.
	 */
	@Test
	void getRemoteAddrTest_shouldReturnUnknownHeader() {
		var addrStr = "127.0.0.1";
		var mockRequest = new MockHttpServletRequest();
		mockRequest.setRemoteAddr(addrStr);
		mockRequest.addHeader("UnknownHeader", addrStr);

		var response = HttpUtil.getRemoteAddr(mockRequest);

		assertEquals(addrStr, response);
	}
}
