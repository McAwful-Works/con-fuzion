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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The fields we read from Discord's {@code GET /users/@me} with the
 * {@code identify} scope. Everything else Discord sends is ignored, and never
 * stored.
 *
 * @param id         the Discord user ID (a snowflake, as a string)
 * @param username   the Discord username
 * @param globalName the display name, or {@code null}
 * @param avatar     the avatar hash, or {@code null} for the default avatar
 * @param mfaEnabled whether the account has MFA on. Discord marks it optional,
 *                   so {@code null} counts as off
 * @author mcawful
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DiscordUser(String id, String username, @JsonProperty("global_name") String globalName, String avatar,
		@JsonProperty("mfa_enabled") Boolean mfaEnabled) {
}
