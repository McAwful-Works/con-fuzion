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

import java.util.List;
import java.util.UUID;

import app.confuzion.backend.model.ResponseBodyObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/**
 * One of the signed-in user's circles. Circles are private to their owner:
 * members aren't told they're in one.
 *
 * @param id      the circle's public ID, used in URLs
 * @param name    the circle's name, unique per owner ignoring case
 * @param members the members, sorted by display name
 * @author mcawful
 */
public record CircleResponse(@Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
		@Schema(requiredMode = RequiredMode.REQUIRED, example = "Cosplay crew") String name,
		@Schema(requiredMode = RequiredMode.REQUIRED) List<PersonResponse> members) implements ResponseBodyObject {
}
