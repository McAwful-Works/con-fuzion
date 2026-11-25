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

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.zerodeplibs.webpush.VAPIDKeyPair;
import com.zerodeplibs.webpush.VAPIDKeyPairs;
import com.zerodeplibs.webpush.key.PrivateKeySources;
import com.zerodeplibs.webpush.key.PublicKeySources;

/**
 * The server's VAPID key pair (RFC 8292), from
 * {@code con-fuzion.push.vapid.public-key} and {@code private-key}: base64url,
 * the public key as an uncompressed P-256 point (65 bytes) and the private key
 * as its raw scalar (32 bytes), the format {@code web-push generate-vapid-keys}
 * prints. With neither set, push is off: {@link #publicKey()} is empty and
 * nothing is sent (D-014 rule 14). Setting only one, a malformed key, or a pair
 * that doesn't match stops startup. Error messages never include a key.
 *
 * @author mcawful
 */
@Component
public class VapidKeys {
	/**
	 * The key pair, or {@code null} when push is off.
	 */
	private final VAPIDKeyPair keyPair;

	/**
	 * The public key, base64url without padding, or {@code null} when push is off.
	 */
	private final String publicKey;

	/**
	 * Reads the key pair.
	 *
	 * @param publicKey  the base64url public key, or blank for no push
	 * @param privateKey the base64url private key, or blank for no push
	 * @throws IllegalStateException when only one is set, or they aren't a valid
	 *                               P-256 pair
	 */
	public VapidKeys(@Value("${con-fuzion.push.vapid.public-key:}") String publicKey,
			@Value("${con-fuzion.push.vapid.private-key:}") String privateKey) {
		if (publicKey.isBlank() && privateKey.isBlank()) {
			this.keyPair = null;
			this.publicKey = null;
			return;
		}
		if (publicKey.isBlank() || privateKey.isBlank())
			throw new IllegalStateException("Set both VAPID keys, or neither to turn push off");
		this.keyPair = parse(publicKey.strip(), privateKey.strip());
		// Served without padding, as PushKeyResponse promises, however it was pasted
		// in.
		this.publicKey = Base64.getUrlEncoder().withoutPadding()
				.encodeToString(Base64.getUrlDecoder().decode(publicKey.strip()));
	}

	/**
	 * Returns the public key for browsers to subscribe with.
	 *
	 * @return the base64url public key, or empty when push is off
	 */
	public Optional<String> publicKey() {
		return Optional.ofNullable(publicKey);
	}

	/**
	 * Returns the key pair that signs each push's VAPID JWT.
	 *
	 * @return the key pair, or empty when push is off
	 */
	public Optional<VAPIDKeyPair> keyPair() {
		return Optional.ofNullable(keyPair);
	}

	/**
	 * Builds the key pair, and checks that the private key signs what the public
	 * key verifies, so a mismatched pair fails at startup instead of on every push.
	 *
	 * @param publicKey  the base64url uncompressed public key
	 * @param privateKey the base64url raw private scalar
	 * @return the key pair
	 * @throws IllegalStateException when the keys are malformed or don't match
	 */
	private static VAPIDKeyPair parse(String publicKey, String privateKey) {
		ECPrivateKey key;
		ECPublicKey point;
		try {
			var publicBytes = Base64.getUrlDecoder().decode(publicKey);
			var privateBytes = Base64.getUrlDecoder().decode(privateKey);
			if (publicBytes.length != 65 || publicBytes[0] != 4 || privateBytes.length != 32)
				throw new IllegalStateException("The VAPID keys aren't in the expected format");
			var parameters = AlgorithmParameters.getInstance("EC");
			parameters.init(new ECGenParameterSpec("secp256r1"));
			var curve = parameters.getParameterSpec(ECParameterSpec.class);
			var factory = KeyFactory.getInstance("EC");
			key = (ECPrivateKey) factory.generatePrivate(new ECPrivateKeySpec(new BigInteger(1, privateBytes), curve));
			point = (ECPublicKey) factory.generatePublic(
					new ECPublicKeySpec(new ECPoint(new BigInteger(1, Arrays.copyOfRange(publicBytes, 1, 33)),
							new BigInteger(1, Arrays.copyOfRange(publicBytes, 33, 65))), curve));
			var probe = "con-fuzion VAPID key check".getBytes(StandardCharsets.US_ASCII);
			var signer = Signature.getInstance("SHA256withECDSA");
			signer.initSign(key);
			signer.update(probe);
			var verifier = Signature.getInstance("SHA256withECDSA");
			verifier.initVerify(point);
			verifier.update(probe);
			if (!verifier.verify(signer.sign()))
				throw new IllegalStateException("The VAPID public key doesn't match the private key");
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			// The cause can quote key material, so it's left out.
			throw new IllegalStateException("The VAPID keys aren't a valid P-256 key pair");
		}
		return VAPIDKeyPairs.of(PrivateKeySources.ofECPrivateKey(key), PublicKeySources.ofECPublicKey(point));
	}
}
