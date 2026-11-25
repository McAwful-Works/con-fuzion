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

import app.confuzion.backend.model.ResponseBodyObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/**
 * Who is going, returned by {@code GET /api/events/{eventId}/attendees} to the
 * host and confirmed attendees only (D-013).
 *
 * @param confirmed the people holding a spot, in the order they joined, so the
 *                  host comes first
 * @param waitlist  for the host only, the waitlist in queue order; {@code null}
 *                  for everyone else
 * @param offered   for the host only, the people holding an open offer, in
 *                  queue order; {@code null} for everyone else
 * @author mcawful
 */
public record AttendeesResponse(
		@Schema(requiredMode = RequiredMode.REQUIRED, description = "People holding a spot, in the order they joined, so the host comes first. Leaves out anyone who has blocked the caller or whom the caller has blocked.") List<PersonResponse> confirmed,
		@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "Only the host gets this: the waitlist in queue order, next in line first. Null for everyone else.") List<PersonResponse> waitlist,
		@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "Only the host gets this: the people holding an open offer, in queue order (D-014). Their spots count as taken. Null for everyone else.") List<PersonResponse> offered)
		implements ResponseBodyObject {
}
