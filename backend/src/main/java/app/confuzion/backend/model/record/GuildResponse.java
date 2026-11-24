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
package app.confuzion.backend.model.record;

import app.confuzion.backend.model.ResponseBodyObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/**
 * One of the signed-in user's Discord servers, as of their last login sync.
 *
 * @param id           the Discord server (guild) ID. It is a snowflake, sent as
 *                     a string because it doesn't fit in a JavaScript number
 * @param name         the server name
 * @param iconUrl      the Discord CDN icon URL, or {@code null} when the server
 *                     has no icon
 * @param discoverable whether the user lets people in this server find them. It
 *                     is off until the user turns it on
 * @author mcawful
 */
public record GuildResponse(
		@Schema(requiredMode = RequiredMode.REQUIRED, pattern = GuildResponse.SNOWFLAKE_PATTERN, example = "81384788765712384") String id,
		@Schema(requiredMode = RequiredMode.REQUIRED, example = "Example Con Community") String name,
		@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, example = "https://cdn.discordapp.com/icons/81384788765712384/0123456789abcdef0123456789abcdef.png") String iconUrl,
		@Schema(requiredMode = RequiredMode.REQUIRED) boolean discoverable) implements ResponseBodyObject {
	/**
	 * A Discord snowflake ID: an unsigned 64-bit integer written in decimal.
	 */
	public static final String SNOWFLAKE_PATTERN = "^[0-9]{17,20}$";
}
