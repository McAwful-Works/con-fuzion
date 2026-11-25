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
 * A user's granted roles, as an admin sees them (D-015).
 *
 * @param roles      the granted roles, in declaration order, whether or not
 *                   they are in effect
 * @param mfaEnabled whether Discord reported MFA on at the user's last sign-in,
 *                   which the roles need to be in effect
 * @author mcawful
 */
public record RolesResponse(
		@Schema(requiredMode = RequiredMode.REQUIRED, description = "Granted roles, MODERATOR before ADMIN. Empty for a plain user.") List<GrantedRole> roles,
		@Schema(requiredMode = RequiredMode.REQUIRED, description = "Whether Discord reported MFA on at the user's last sign-in. Without it the roles aren't in effect.") boolean mfaEnabled)
		implements ResponseBodyObject {
}
