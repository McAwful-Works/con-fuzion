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

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Reports an event or a user to the moderators (D-015). The reporter is the
 * caller, never a field of the request.
 *
 * @param targetType whether an event or a user is reported
 * @param targetId   the event's public ID or the user's ID
 * @param reason     why
 * @param note       what happened, as plain text, or {@code null}
 * @author mcawful
 */
public record ReportRequest(@Schema(requiredMode = RequiredMode.REQUIRED) @NotNull ReportResponse.TargetType targetType,
		@Schema(requiredMode = RequiredMode.REQUIRED, description = "The event's public ID, or the user's ID.") @NotNull UUID targetId,
		@Schema(requiredMode = RequiredMode.REQUIRED) @NotNull ReportResponse.Reason reason,
		@Schema(nullable = true, maxLength = 1000, description = "Optional. What happened, as plain text.") @Size(max = 1000) String note) {
}
