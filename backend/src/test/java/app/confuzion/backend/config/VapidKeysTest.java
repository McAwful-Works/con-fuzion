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
package app.confuzion.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import app.confuzion.backend.service.TestBrowser;

/**
 * Unit tests for {@link VapidKeys}, with key pairs generated at run time: no
 * keys means push is off, and a half-set, malformed or mismatched pair stops
 * startup without quoting a key.
 *
 * @author mcawful
 */
class VapidKeysTest {
	/**
	 * Tests that neither key means push is off.
	 */
	@Test
	void testNoKeysTurnsPushOff() {
		var keys = new VapidKeys(" ", "");
		assertThat(keys.publicKey()).isEmpty();
		assertThat(keys.keyPair()).isEmpty();
	}

	/**
	 * Tests that a matching pair is read, and the public key is served without
	 * padding even when it was configured with it.
	 */
	@Test
	void testReadsAMatchingPair() {
		var pair = TestBrowser.vapidKeys();
		var keys = new VapidKeys(pair[0], " " + pair[1] + "\n");
		assertThat(keys.publicKey()).contains(pair[0]);
		assertThat(keys.keyPair()).hasValueSatisfying(
				keyPair -> assertThat(keyPair.extractPublicKeyInUncompressedFormAsString()).isEqualTo(pair[0]));
		assertThat(new VapidKeys(pair[0] + "=", pair[1] + "=").publicKey()).contains(pair[0]);
	}

	/**
	 * Tests that only one key, a malformed one or a mismatched pair fails, and the
	 * message never holds the private key.
	 */
	@Test
	void testRefusesBadKeys() {
		var pair = TestBrowser.vapidKeys();
		var other = TestBrowser.vapidKeys();
		for (var bad : new String[][] { { pair[0], "" }, { "", pair[1] }, { pair[0], other[1] },
				{ pair[0], pair[1].substring(2) }, { pair[0].substring(1), pair[1] }, { pair[0], "not*base64" },
				{ pair[0].substring(0, 86) + (pair[0].charAt(86) == 'A' ? 'Q' : 'A'), pair[1] } })
			assertThatThrownBy(() -> new VapidKeys(bad[0], bad[1])).isInstanceOf(IllegalStateException.class).message()
					.doesNotContain(pair[1]).doesNotContain(other[1]);
	}
}
