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
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import tools.jackson.databind.json.JsonMapper;

/**
 * A stand-in for the push services, for tests: an in-process {@link HttpClient}
 * that records every request it gets and answers 201, or whatever a test sets
 * for an endpoint. Requests keep their real https URLs on the allowed hosts, so
 * the sender's own host check runs unchanged, and nothing leaves the JVM.
 * {@link #verifyVapid} checks a request's VAPID JWT the way a push service
 * would (RFC 8292).
 *
 * @author mcawful
 */
final class StubPushService extends HttpClient {
	/**
	 * Reads JWT segments.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Every request received, in order.
	 */
	private final List<Received> received = new CopyOnWriteArrayList<>();

	/**
	 * The answer for each endpoint that doesn't get a 201.
	 */
	private final Map<String, Answer> answers = new ConcurrentHashMap<>();

	/**
	 * How long each answer takes.
	 */
	private volatile Duration delay = Duration.ZERO;

	/**
	 * Makes an endpoint get a status, with an optional {@code Retry-After}.
	 *
	 * @param endpoint   the endpoint
	 * @param status     the status code
	 * @param retryAfter the {@code Retry-After} value, or {@code null}
	 */
	void answer(String endpoint, int status, String retryAfter) {
		answers.put(endpoint, new Answer(status, retryAfter));
	}

	/**
	 * Makes every answer take a while, so concurrent senders overlap.
	 *
	 * @param delay how long
	 */
	void delay(Duration delay) {
		this.delay = delay;
	}

	/**
	 * Returns the requests received for an endpoint.
	 *
	 * @param endpoint the endpoint
	 * @return the requests, in order
	 */
	List<Received> receivedBy(String endpoint) {
		return received.stream().filter(r -> r.uri().toString().equals(endpoint)).toList();
	}

	/**
	 * Verifies a request's {@code Authorization: vapid t=..., k=...} header: the
	 * key is the server's, and the JWT is ES256 signed by it.
	 *
	 * @param received  the request
	 * @param publicKey the server's VAPID public key, base64url
	 * @return the JWT's claims
	 * @throws GeneralSecurityException when it doesn't verify
	 */
	static Map<String, Object> verifyVapid(Received received, String publicKey) throws GeneralSecurityException {
		var header = received.headers().firstValue("Authorization").orElseThrow();
		if (!header.startsWith("vapid "))
			throw new GeneralSecurityException("Not a vapid authorization");
		String token = null;
		String key = null;
		for (var part : header.substring("vapid ".length()).split(",")) {
			var pair = part.strip().split("=", 2);
			if (pair[0].equals("t"))
				token = pair[1];
			else if (pair[0].equals("k"))
				key = pair[1];
		}
		if (token == null || !publicKey.equals(key))
			throw new GeneralSecurityException("The token or the key is missing or wrong");
		var segments = token.split("\\.");
		var joseHeader = JSON.readValue(Base64.getUrlDecoder().decode(segments[0]), Map.class);
		if (!"ES256".equals(joseHeader.get("alg")))
			throw new GeneralSecurityException("Not ES256");
		var verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
		verifier.initVerify(TestBrowser.publicKey(Base64.getUrlDecoder().decode(key)));
		verifier.update((segments[0] + "." + segments[1]).getBytes(StandardCharsets.US_ASCII));
		if (!verifier.verify(Base64.getUrlDecoder().decode(segments[2])))
			throw new GeneralSecurityException("The JWT signature doesn't verify");
		@SuppressWarnings("unchecked")
		Map<String, Object> claims = JSON.readValue(Base64.getUrlDecoder().decode(segments[1]), Map.class);
		return claims;
	}

	/**
	 * Records a request and answers it.
	 *
	 * @param <T>         the body type, unused: the body is always {@code null}
	 * @param request     the request
	 * @param bodyHandler unused
	 * @return the answer
	 * @throws InterruptedException when interrupted during the delay
	 */
	@Override
	public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler)
			throws InterruptedException {
		received.add(new Received(request.uri(), request.headers(), read(request), request.timeout()));
		if (!delay.isZero())
			Thread.sleep(delay);
		var answer = answers.getOrDefault(request.uri().toString(), new Answer(201, null));
		var headers = answer.retryAfter() == null ? Map.<String, List<String>>of()
				: Map.of("Retry-After", List.of(answer.retryAfter()));
		return new Response<>(answer.status(), request, HttpHeaders.of(headers, (a, b) -> true));
	}

	/**
	 * Sends on the calling thread.
	 *
	 * @param <T>         the body type
	 * @param request     the request
	 * @param bodyHandler unused
	 * @return the answer
	 */
	@Override
	public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
			HttpResponse.BodyHandler<T> bodyHandler) {
		try {
			return CompletableFuture.completedFuture(send(request, bodyHandler));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return CompletableFuture.failedFuture(e);
		}
	}

	/**
	 * Sends on the calling thread.
	 *
	 * @param <T>                the body type
	 * @param request            the request
	 * @param bodyHandler        unused
	 * @param pushPromiseHandler unused
	 * @return the answer
	 */
	@Override
	public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
			HttpResponse.BodyHandler<T> bodyHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
		return sendAsync(request, bodyHandler);
	}

	/**
	 * Returns no cookie handler.
	 *
	 * @return empty
	 */
	@Override
	public Optional<CookieHandler> cookieHandler() {
		return Optional.empty();
	}

	/**
	 * Returns no connect timeout.
	 *
	 * @return empty
	 */
	@Override
	public Optional<Duration> connectTimeout() {
		return Optional.empty();
	}

	/**
	 * Never follows redirects.
	 *
	 * @return {@link HttpClient.Redirect#NEVER}
	 */
	@Override
	public Redirect followRedirects() {
		return Redirect.NEVER;
	}

	/**
	 * Returns no proxy.
	 *
	 * @return empty
	 */
	@Override
	public Optional<ProxySelector> proxy() {
		return Optional.empty();
	}

	/**
	 * Returns the default TLS context.
	 *
	 * @return the context
	 */
	@Override
	public SSLContext sslContext() {
		try {
			return SSLContext.getDefault();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Returns default TLS parameters.
	 *
	 * @return the parameters
	 */
	@Override
	public SSLParameters sslParameters() {
		return new SSLParameters();
	}

	/**
	 * Returns no authenticator.
	 *
	 * @return empty
	 */
	@Override
	public Optional<Authenticator> authenticator() {
		return Optional.empty();
	}

	/**
	 * Returns HTTP/1.1.
	 *
	 * @return the version
	 */
	@Override
	public Version version() {
		return Version.HTTP_1_1;
	}

	/**
	 * Returns no executor.
	 *
	 * @return empty
	 */
	@Override
	public Optional<Executor> executor() {
		return Optional.empty();
	}

	/**
	 * Reads a request's body.
	 *
	 * @param request the request
	 * @return the bytes, empty for none
	 */
	private static byte[] read(HttpRequest request) {
		var publisher = request.bodyPublisher();
		if (publisher.isEmpty())
			return new byte[0];
		var out = new ByteArrayOutputStream();
		var done = new CompletableFuture<byte[]>();
		publisher.get().subscribe(new Flow.Subscriber<ByteBuffer>() {
			/**
			 * Asks for everything.
			 *
			 * @param subscription the subscription
			 */
			@Override
			public void onSubscribe(Flow.Subscription subscription) {
				subscription.request(Long.MAX_VALUE);
			}

			/**
			 * Collects a buffer.
			 *
			 * @param item the buffer
			 */
			@Override
			public void onNext(ByteBuffer item) {
				var bytes = new byte[item.remaining()];
				item.get(bytes);
				out.writeBytes(bytes);
			}

			/**
			 * Fails the read.
			 *
			 * @param throwable the error
			 */
			@Override
			public void onError(Throwable throwable) {
				done.completeExceptionally(throwable);
			}

			/**
			 * Finishes the read.
			 */
			@Override
			public void onComplete() {
				done.complete(out.toByteArray());
			}
		});
		return done.join();
	}

	/**
	 * A request the stub got.
	 *
	 * @param uri     the endpoint
	 * @param headers the headers
	 * @param body    the encrypted body
	 * @param timeout the request's timeout
	 * @author mcawful
	 */
	record Received(URI uri, HttpHeaders headers, byte[] body, Optional<Duration> timeout) {
	}

	/**
	 * How the stub answers an endpoint.
	 *
	 * @param status     the status code
	 * @param retryAfter the {@code Retry-After} value, or {@code null}
	 * @author mcawful
	 */
	private record Answer(int status, String retryAfter) {
	}

	/**
	 * A bodiless answer.
	 *
	 * @param <T>        the body type
	 * @param statusCode the status code
	 * @param request    the request
	 * @param headers    the headers
	 * @author mcawful
	 */
	private record Response<T>(int statusCode, HttpRequest request, HttpHeaders headers) implements HttpResponse<T> {
		/**
		 * Returns no earlier response.
		 *
		 * @return empty
		 */
		@Override
		public Optional<HttpResponse<T>> previousResponse() {
			return Optional.empty();
		}

		/**
		 * Returns no body.
		 *
		 * @return {@code null}
		 */
		@Override
		public T body() {
			return null;
		}

		/**
		 * Returns no TLS session.
		 *
		 * @return empty
		 */
		@Override
		public Optional<SSLSession> sslSession() {
			return Optional.empty();
		}

		/**
		 * Returns the request's URI.
		 *
		 * @return the URI
		 */
		@Override
		public URI uri() {
			return request.uri();
		}

		/**
		 * Returns HTTP/1.1.
		 *
		 * @return the version
		 */
		@Override
		public HttpClient.Version version() {
			return HttpClient.Version.HTTP_1_1;
		}
	}
}
