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

import java.util.Map;

import app.confuzion.backend.model.ResponseBodyObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/**
 * The standard response envelope. Every {@code /api} body uses it, success and
 * error alike (D-010), so clients parse one structure and switch on
 * {@code responseType}. The type parameter lets the OpenAPI document describe
 * each endpoint's concrete body.
 *
 * @param <T>          the body type
 * @param responseType the {@link ResponseType}
 * @param body         the {@link ResponseBodyObject}
 *
 * @author mcawful
 */
public record ResponseRecord<T extends ResponseBodyObject>(
		@Schema(requiredMode = RequiredMode.REQUIRED) ResponseType responseType,
		@Schema(requiredMode = RequiredMode.REQUIRED) T body) {
	/**
	 * The response body to type map.
	 */
	private static final Map<Class<? extends ResponseBodyObject>, ResponseType> BODY_TO_TYPE_MAP = Map.ofEntries(
			Map.entry(ErrorResponseBodyRecord.class, ResponseType.ERROR), Map.entry(MeResponse.class, ResponseType.ME),
			Map.entry(GuildResponse.class, ResponseType.GUILD),
			Map.entry(GuildListResponse.class, ResponseType.GUILD_LIST),
			Map.entry(PersonResponse.class, ResponseType.PERSON),
			Map.entry(PersonListResponse.class, ResponseType.PERSON_LIST),
			Map.entry(InviteResponse.class, ResponseType.INVITE),
			Map.entry(InvitePreviewResponse.class, ResponseType.INVITE_PREVIEW),
			Map.entry(CircleResponse.class, ResponseType.CIRCLE),
			Map.entry(CircleListResponse.class, ResponseType.CIRCLE_LIST));

	/**
	 * Constructor to create a {@link ResponseRecord} from the provided
	 * {@link ResponseBodyObject}
	 *
	 * @param responseBodyObject the {@link ResponseBodyObject}
	 */
	public ResponseRecord(T responseBodyObject) {
		this(getResponseType(responseBodyObject), responseBodyObject);
	}

	/**
	 * Returns a {@link ResponseType} from the provided {@link ResponseBodyObject}
	 * class.
	 *
	 * @param responseBodyObject a {@link ResponseBodyObject}
	 * @return a {@link ResponseType}
	 */
	private static ResponseType getResponseType(ResponseBodyObject responseBodyObject) {
		return BODY_TO_TYPE_MAP.getOrDefault(responseBodyObject.getClass(), ResponseType.UNKNOWN);
	}
}
