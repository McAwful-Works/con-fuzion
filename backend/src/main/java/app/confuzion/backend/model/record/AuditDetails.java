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

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * An audit row's own fields (D-015), stored in {@code audit_log.details}. Each
 * {@link AuditEntryResponse.Action} sets only its own fields, and the others
 * are left out. Details hold IDs, enums, times and moderators' own text only:
 * never a name, a reporter's note, a token or an IP address.
 *
 * @param reason     the moderator's or admin's reason, for
 *                   {@code EVENT_HIDDEN}, {@code EVENT_CANCELLED},
 *                   {@code USER_SUSPENDED} and {@code ACCOUNT_BANNED}
 * @param until      for {@code USER_SUSPENDED}, when it ends
 * @param resolution for {@code REPORT_RESOLVED}, how the report was closed
 * @param note       for {@code REPORT_RESOLVED}, the moderator's note
 * @param roles      for {@code ROLES_CHANGED}, the user's new granted roles
 * @param cause      for {@code SESSIONS_PURGED} and {@code SIGN_IN_REFUSED},
 *                   the action behind it
 * @param sessions   for {@code SESSIONS_PURGED}, how many were deleted
 * @author mcawful
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditDetails(
		@Schema(description = "EVENT_HIDDEN, EVENT_CANCELLED, USER_SUSPENDED and ACCOUNT_BANNED: the reason given.", maxLength = 500) String reason,
		@Schema(description = "USER_SUSPENDED only: when the suspension ends.") Instant until,
		@Schema(description = "REPORT_RESOLVED only.") ResolveReportRequest.Resolution resolution,
		@Schema(description = "REPORT_RESOLVED only: the moderator's resolution note.", maxLength = 1000) String note,
		@Schema(description = "ROLES_CHANGED only: the full new set, possibly empty.") List<GrantedRole> roles,
		@Schema(description = "SESSIONS_PURGED (ROLES_CHANGED, USER_SUSPENDED or ACCOUNT_BANNED) and SIGN_IN_REFUSED (USER_SUSPENDED or ACCOUNT_BANNED): the action behind it.") AuditEntryResponse.Action cause,
		@Schema(description = "SESSIONS_PURGED only: how many sessions were deleted.", minimum = "0") Integer sessions) {
}
