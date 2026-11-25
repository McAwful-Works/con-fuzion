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

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A browser's side of Web Push, for tests: a P-256 key pair and an
 * authentication secret generated at run time, and the RFC 8291
 * ({@code aes128gcm}) decryption a browser does. Nothing here is a fixture:
 * every key is thrown away when the test ends.
 *
 * @author mcawful
 */
public final class TestBrowser {
	/**
	 * The browser's key pair.
	 */
	private final KeyPair keyPair;

	/**
	 * The 16-byte authentication secret.
	 */
	private final byte[] auth;

	/**
	 * Creates a browser with fresh keys.
	 */
	TestBrowser() {
		this.keyPair = generateKeyPair();
		this.auth = new byte[16];
		new SecureRandom().nextBytes(auth);
	}

	/**
	 * Returns the browser's public key as a subscription carries it.
	 *
	 * @return the base64url uncompressed point
	 */
	String p256dh() {
		return base64Url(uncompressed((ECPublicKey) keyPair.getPublic()));
	}

	/**
	 * Returns the authentication secret as a subscription carries it.
	 *
	 * @return the base64url secret
	 */
	String auth() {
		return base64Url(auth);
	}

	/**
	 * Decrypts a push message body (RFC 8291 and RFC 8188, one record).
	 *
	 * @param body the request body
	 * @return the plaintext
	 * @throws GeneralSecurityException when it doesn't decrypt with this browser's
	 *                                  keys
	 */
	String decrypt(byte[] body) throws GeneralSecurityException {
		var buffer = ByteBuffer.wrap(body);
		var salt = new byte[16];
		buffer.get(salt);
		buffer.getInt(); // the record size
		var serverKey = new byte[buffer.get() & 0xff];
		buffer.get(serverKey);
		var ciphertext = new byte[buffer.remaining()];
		buffer.get(ciphertext);

		var agreement = KeyAgreement.getInstance("ECDH");
		agreement.init(keyPair.getPrivate());
		agreement.doPhase(publicKey(serverKey), true);
		var prkKey = hmac(auth, agreement.generateSecret());
		var keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII),
				uncompressed((ECPublicKey) keyPair.getPublic()), serverKey, new byte[] { 1 });
		var ikm = Arrays.copyOf(hmac(prkKey, keyInfo), 32);
		var prk = hmac(salt, ikm);
		var cek = Arrays.copyOf(
				hmac(prk,
						concat("Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), new byte[] { 1 })),
				16);
		var nonce = Arrays.copyOf(
				hmac(prk, concat("Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), new byte[] { 1 })),
				12);

		var cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
		var plain = cipher.doFinal(ciphertext);
		var end = plain.length - 1;
		while (end > 0 && plain[end] == 0)
			end--;
		if (plain[end] != 2)
			throw new GeneralSecurityException("The last record's padding delimiter is missing");
		return new String(plain, 0, end, StandardCharsets.UTF_8);
	}

	/**
	 * Generates a throwaway VAPID key pair in the format the app reads.
	 *
	 * @return the base64url public key (uncompressed point) and private key (raw
	 *         scalar)
	 */
	public static String[] vapidKeys() {
		var pair = generateKeyPair();
		return new String[] { base64Url(uncompressed((ECPublicKey) pair.getPublic())),
				base64Url(fixed(((ECPrivateKey) pair.getPrivate()).getS())) };
	}

	/**
	 * Generates a P-256 key pair.
	 *
	 * @return the key pair
	 */
	static KeyPair generateKeyPair() {
		try {
			var generator = KeyPairGenerator.getInstance("EC");
			generator.initialize(new ECGenParameterSpec("secp256r1"));
			return generator.generateKeyPair();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Reads an uncompressed P-256 point.
	 *
	 * @param point the 65 bytes
	 * @return the public key
	 * @throws GeneralSecurityException when it isn't a P-256 point
	 */
	static ECPublicKey publicKey(byte[] point) throws GeneralSecurityException {
		var curve = ((ECPublicKey) generateKeyPair().getPublic()).getParams();
		return (ECPublicKey) KeyFactory.getInstance("EC")
				.generatePublic(new ECPublicKeySpec(new ECPoint(new BigInteger(1, Arrays.copyOfRange(point, 1, 33)),
						new BigInteger(1, Arrays.copyOfRange(point, 33, 65))), curve));
	}

	/**
	 * Encodes a public key as an uncompressed point.
	 *
	 * @param key the key
	 * @return the 65 bytes
	 */
	static byte[] uncompressed(ECPublicKey key) {
		return concat(new byte[] { 4 }, fixed(key.getW().getAffineX()), fixed(key.getW().getAffineY()));
	}

	/**
	 * Encodes bytes as base64url without padding.
	 *
	 * @param bytes the bytes
	 * @return the text
	 */
	static String base64Url(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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

	/**
	 * Computes an HMAC-SHA-256.
	 *
	 * @param key  the key
	 * @param data the data
	 * @return the MAC
	 * @throws GeneralSecurityException never, in practice
	 */
	private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
		var mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(key, "HmacSHA256"));
		return mac.doFinal(data);
	}

	/**
	 * Joins byte arrays.
	 *
	 * @param parts the arrays
	 * @return the joined bytes
	 */
	private static byte[] concat(byte[]... parts) {
		var out = new ByteArrayOutputStream();
		for (var part : parts)
			out.writeBytes(part);
		return out.toByteArray();
	}
}
