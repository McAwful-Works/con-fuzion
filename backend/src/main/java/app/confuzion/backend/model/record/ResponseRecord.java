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

import java.util.HashMap;
import java.util.Map;

import app.confuzion.backend.model.ResponseBodyObject;

/**
 * The standard response record.
 * 
 * @param responseType the {@link ResponseType}
 * @param body         the {@link ResponseBodyObject}
 * 
 * @author mcawful
 */
public record ResponseRecord(ResponseType responseType, ResponseBodyObject body) {
	/**
	 * The response body to type map.
	 */
	private static final Map<Class<? extends ResponseBodyObject>, ResponseType> BODY_TO_TYPE_MAP = new HashMap<>();

	// Initializes the mapping
	static {
		BODY_TO_TYPE_MAP.put(ErrorResponseBodyRecord.class, ResponseType.ERROR);
	}

	/**
	 * Constructor to create a {@link ResponseRecord} from the provided
	 * {@link ResponseBodyObject}
	 *
	 * @param responseBodyObject the {@link ResponseBodyObject}
	 */
	public ResponseRecord(ResponseBodyObject responseBodyObject) {
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
		var responseBodyObjectClass = responseBodyObject.getClass();
		if (BODY_TO_TYPE_MAP.containsKey(responseBodyObjectClass))
			return BODY_TO_TYPE_MAP.get(responseBodyObjectClass);
		return ResponseType.UNKNOWN;
	}
}
