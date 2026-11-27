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

/**
 * The optional search and filters of the Discover feed (D-017). They only
 * narrow the events the viewer can already see.
 *
 * @param q        text to find in the title, tag or description, never the
 *                 location, or {@code null}
 * @param guild    a Discord server ID whose events the viewer sees through it,
 *                 or {@code null}
 * @param friends  {@code true} for events open to the host's friends, the
 *                 viewer being one, or {@code null}
 * @param hasSpots {@code true} for unlimited events and events with spots left,
 *                 or {@code null}
 * @author mcawful
 */
public record FeedFilter(String q, String guild, Boolean friends, Boolean hasSpots) {

	/**
	 * No search and no filters.
	 */
	public static final FeedFilter NONE = new FeedFilter(null, null, null, null);

	/**
	 * Returns whether no parameter was given at all, {@code false} values included.
	 *
	 * @return {@code true} when every field is {@code null}
	 */
	public boolean isEmpty() {
		return q == null && guild == null && friends == null && hasSpots == null;
	}
}
