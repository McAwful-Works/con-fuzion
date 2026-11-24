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

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /api/circles} and {@code PATCH
 * /api/circles/{circleId}}.
 *
 * @param name the circle's name, 1 to 50 characters with no leading or trailing
 *             whitespace. It must be unique among the owner's circles, ignoring
 *             case
 * @author mcawful
 */
public record CircleRequest(
		@Schema(requiredMode = RequiredMode.REQUIRED, example = "Cosplay crew") @NotNull @Size(min = 1, max = CircleRequest.MAX_NAME) @Pattern(regexp = CircleRequest.TRIMMED) String name) {
	/**
	 * The longest circle name, matching {@code circle.name}.
	 */
	public static final int MAX_NAME = 50;

	/**
	 * Text with no leading or trailing whitespace (and no line breaks), so clients
	 * trim before sending.
	 */
	public static final String TRIMMED = "^\\S(.*\\S)?$";
}
