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
package app.confuzion.backend.service;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * Web Push keys for tests, generated at run time and thrown away when the test
 * ends. Nothing here is a fixture, so no key is ever committed.
 *
 * @author mcawful
 */
public final class TestBrowser {
	/**
	 * Not instantiated.
	 */
	private TestBrowser() {
	}

	/**
	 * Generates a throwaway VAPID key pair in the format the app reads.
	 *
	 * @return the base64url public key (uncompressed point) and private key (raw
	 *         scalar)
	 */
	public static String[] vapidKeys() {
		KeyPair pair;
		try {
			var generator = KeyPairGenerator.getInstance("EC");
			generator.initialize(new ECGenParameterSpec("secp256r1"));
			pair = generator.generateKeyPair();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
		var point = ((ECPublicKey) pair.getPublic()).getW();
		var uncompressed = new byte[65];
		uncompressed[0] = 4;
		System.arraycopy(fixed(point.getAffineX()), 0, uncompressed, 1, 32);
		System.arraycopy(fixed(point.getAffineY()), 0, uncompressed, 33, 32);
		var encoder = Base64.getUrlEncoder().withoutPadding();
		return new String[] { encoder.encodeToString(uncompressed),
				encoder.encodeToString(fixed(((ECPrivateKey) pair.getPrivate()).getS())) };
	}

	/**
	 * Writes a number as exactly 32 big-endian bytes.
	 *
	 * @param value the number, less than 2^256
	 * @return the bytes
	 */
	private static byte[] fixed(BigInteger value) {
		var bytes = value.toByteArray();
		var out = new byte[32];
		var length = Math.min(bytes.length, 32);
		System.arraycopy(bytes, bytes.length - length, out, 32 - length, length);
		return out;
	}
}
