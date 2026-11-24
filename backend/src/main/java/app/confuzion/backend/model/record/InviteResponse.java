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

import app.confuzion.backend.model.ResponseBodyObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/**
 * A new friend invite link, returned only by {@code POST /api/friends/invites}.
 * This is the one place the raw token ever appears; the server keeps only its
 * hash (D-011).
 *
 * @param url       the link to share or show as a QR code. Any phone camera
 *                  opens it; the app's accept page is {@code /invite/{token}}
 * @param expiresAt when the link stops working, 24 hours after it was made
 * @author mcawful
 */
public record InviteResponse(
		@Schema(requiredMode = RequiredMode.REQUIRED, example = "https://example.com/invite/q3Jx0nV9cY2bT8pL1mR4wA") String url,
		@Schema(requiredMode = RequiredMode.REQUIRED) Instant expiresAt) implements ResponseBodyObject {
}
