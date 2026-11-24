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

import java.util.UUID;

import app.confuzion.backend.model.ResponseBodyObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/**
 * Another user's public profile: what friends lists, people search, circles,
 * blocks and event hosts show. Users are identified by their Con-Fuzion ID,
 * never their Discord ID.
 *
 * @param id          the user's Con-Fuzion ID
 * @param displayName the Discord display name, or the username when the user
 *                    has none
 * @param avatarUrl   the Discord CDN avatar URL (Discord's default avatar when
 *                    the user has none)
 * @author mcawful
 */
public record PersonResponse(@Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
		@Schema(requiredMode = RequiredMode.REQUIRED, example = "Con Goer") String displayName,
		@Schema(requiredMode = RequiredMode.REQUIRED, example = "https://cdn.discordapp.com/embed/avatars/0.png") String avatarUrl)
		implements ResponseBodyObject {
}
