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
 * An event's spot counts and the caller's own place, returned by {@code GET
 * /api/events/{eventId}/spots} for polling. Everyone who can see the event gets
 * the counts; the status and position are always the caller's own.
 *
 * @param capacity   the headcount, the host included, or {@code null} for
 *                   unlimited
 * @param confirmed  how many people hold a spot, the host included
 * @param waitlisted how many people are in the waitlist
 * @param spotsLeft  {@code capacity - confirmed}, or {@code null} for unlimited
 * @param myStatus   where the caller stands
 * @param myPosition the caller's place in the waitlist, or {@code null} unless
 *                   they are waitlisted
 * @author mcawful
 */
public record SpotsResponse(
		@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, minimum = "2", maximum = "10000", description = "The headcount, the host included. Null for unlimited.") Integer capacity,
		@Schema(requiredMode = RequiredMode.REQUIRED, minimum = "1", description = "People holding a spot, the host included.") int confirmed,
		@Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0", description = "Always 0 for an unlimited event.") int waitlisted,
		@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, minimum = "0", description = "capacity - confirmed. Null for unlimited.") Integer spotsLeft,
		@Schema(requiredMode = RequiredMode.REQUIRED) MyStatus myStatus,
		@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, minimum = "1", description = "The caller's own 1-based place in the waitlist: 1 + the number of people waitlisted ahead of them. Null unless myStatus is WAITLISTED.") Integer myPosition)
		implements ResponseBodyObject {
}
