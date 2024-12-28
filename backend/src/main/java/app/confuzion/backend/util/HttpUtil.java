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

import jakarta.servlet.http.HttpServletRequest;
import lombok.experimental.UtilityClass;

import java.util.Arrays;
import java.util.List;

/**
 * Utility class that contains methods for HTTP related operations.
 *
 * @author mcawful
 */
@UtilityClass
public class HttpUtil {
    /**
     * The {@link List} of request headers.
     */
    private final List<String> HEADER_LIST = Arrays.asList(
            "host", "connection", "accept", "user-agent", "dnt",
            "referer", "accept-encoding", "accept-language"
    );

    /**
     * The {@link List} of possible headers to fetch the remote IP address from.
     */
    private final List<String> POSSIBLE_ADDR_HEADERS = Arrays.asList(
            "X-Forwarded-For", "Proxy-Client-IP",
            "WL-Proxy-Client-IP", "HTTP_X_FORWARDED_FOR", "HTTP_X_FORWARDED", "HTTP_X_CLUSTER_CLIENT_IP",
            "HTTP_CLIENT_IP", "HTTP_FORWARDED_FOR", "HTTP_FORWARDED", "HTTP_VIA", "REMOTE_ADDR"
    );

    /**
     * Gets and returns the headers as a string from the given {@link HttpServletRequest}.
     *
     * @param request the {@link HttpServletRequest}
     * @return the headers as a string
     */
    public String getRequestHeaders(HttpServletRequest request) {
        var sb = new StringBuilder("[");
        HEADER_LIST.forEach(ele -> sb.append(ele).append(":\"").append(request.getHeader(ele)).append("\", "));
        sb.delete(sb.length() - 2, sb.length());
        sb.append("]");

        return sb.toString();
    }

    /**
     * Gets and returns the remote IP address as a string from the given {@link HttpServletRequest}.
     *
     * @param request the {@link HttpServletRequest}
     * @return the remote IP address as a string
     */
    public String getRemoteAddr(HttpServletRequest request) {
        for (var header : POSSIBLE_ADDR_HEADERS) {
            var ip = request.getHeader(header);
            if (ip != null && !ip.isEmpty() && !ip.equalsIgnoreCase("unknown"))
                return ip;
        }
        return request.getRemoteAddr();
    }
}
