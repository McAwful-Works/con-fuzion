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
package app.confuzion.backend;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A stand-in for Discord's token, {@code /users/@me} and
 * {@code /users/@me/guilds} endpoints, on a loopback port, built on the JDK's
 * own HTTP server so tests need no extra dependency. Each token exchange issues
 * a fresh access token, and the user endpoints accept only an issued one.
 *
 * @author mcawful
 */
public final class DiscordStub implements AutoCloseable {
	/**
	 * The HTTP server.
	 */
	private final HttpServer server;

	/**
	 * Every access and refresh token issued so far.
	 */
	private final List<String> issuedTokens = Collections.synchronizedList(new ArrayList<>());

	/**
	 * The form fields of every token request, in order.
	 */
	private final List<Map<String, String>> tokenRequests = Collections.synchronizedList(new ArrayList<>());

	/**
	 * The counter for token values.
	 */
	private final AtomicInteger tokenCounter = new AtomicInteger();

	/**
	 * The number of guild list calls so far.
	 */
	private final AtomicInteger guildCalls = new AtomicInteger();

	/**
	 * Responses to send for the next {@code /users/@me} calls, before falling back
	 * to {@link #user}.
	 */
	private final Deque<Reply> userReplies = new ConcurrentLinkedDeque<>();

	/**
	 * Responses to send for the next guild list calls, before falling back to
	 * {@link #guilds}.
	 */
	private final Deque<Reply> guildReplies = new ConcurrentLinkedDeque<>();

	/**
	 * The {@code /users/@me} JSON.
	 */
	private volatile String user;

	/**
	 * The guild list JSON.
	 */
	private volatile String guilds = "[]";

	/**
	 * Starts the stub on a free loopback port.
	 */
	public DiscordStub() {
		try {
			server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		server.createContext("/api/oauth2/token", this::token);
		server.createContext("/api/v10/users/@me", this::users);
		server.start();
	}

	/**
	 * Returns the token endpoint URI.
	 *
	 * @return the URI
	 */
	public String tokenUri() {
		return base() + "/api/oauth2/token";
	}

	/**
	 * Returns the user info endpoint URI.
	 *
	 * @return the URI
	 */
	public String userInfoUri() {
		return base() + "/api/v10/users/@me";
	}

	/**
	 * Sets the user {@code /users/@me} returns, and the guilds the guild list
	 * returns, and clears queued replies.
	 *
	 * @param discordId  the Discord ID
	 * @param username   the username
	 * @param mfaEnabled the MFA flag
	 * @param guilds     alternating guild ID and name values
	 */
	public void reset(String discordId, String username, boolean mfaEnabled, String... guilds) {
		user = "{\"id\":\"" + discordId + "\",\"username\":\"" + username + "\",\"global_name\":null,"
				+ "\"avatar\":null,\"discriminator\":\"0\",\"mfa_enabled\":" + mfaEnabled + ",\"locale\":\"en-US\"}";
		setGuilds(guilds);
		userReplies.clear();
		guildReplies.clear();
		guildCalls.set(0);
	}

	/**
	 * Sets the guilds the guild list returns.
	 *
	 * @param guilds alternating guild ID and name values
	 */
	public void setGuilds(String... guilds) {
		var entries = new ArrayList<String>();
		for (var i = 0; i < guilds.length; i += 2)
			entries.add("{\"id\":\"" + guilds[i] + "\",\"name\":\"" + guilds[i + 1] + "\",\"icon\":\"icon" + i
					+ "\",\"owner\":false,\"permissions\":\"0\",\"features\":[]}");
		this.guilds = "[" + String.join(",", entries) + "]";
	}

	/**
	 * Queues a {@code 429} for the next guild list call.
	 *
	 * @param retryAfterSeconds the {@code retry_after} value
	 */
	public void queueGuildRateLimit(double retryAfterSeconds) {
		guildReplies.add(rateLimit(retryAfterSeconds));
	}

	/**
	 * Queues a server error for the next {@code /users/@me} call.
	 */
	public void queueUserFailure() {
		userReplies.add(new Reply(500, "{}", Map.of()));
	}

	/**
	 * Returns the number of guild list calls since the last reset.
	 *
	 * @return the count
	 */
	public int guildCalls() {
		return guildCalls.get();
	}

	/**
	 * Returns every token issued so far.
	 *
	 * @return the tokens
	 */
	public List<String> issuedTokens() {
		return List.copyOf(issuedTokens);
	}

	/**
	 * Returns the form fields of the latest token request.
	 *
	 * @return the fields
	 */
	public Map<String, String> lastTokenRequest() {
		return tokenRequests.getLast();
	}

	/**
	 * Stops the stub.
	 */
	@Override
	public void close() {
		server.stop(0);
	}

	/**
	 * Returns the stub's base URL.
	 *
	 * @return the base URL
	 */
	private String base() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	/**
	 * Handles the token exchange.
	 *
	 * @param exchange the {@link HttpExchange}
	 * @throws IOException when the response fails
	 */
	private void token(HttpExchange exchange) throws IOException {
		var form = new HashMap<String, String>();
		for (var pair : new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
			var parts = pair.split("=", 2);
			if (parts.length == 2)
				form.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
						URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
		}
		tokenRequests.add(form);
		var n = tokenCounter.incrementAndGet();
		var access = "stub-access-token-" + n;
		var refresh = "stub-refresh-token-" + n;
		issuedTokens.addAll(List.of(access, refresh));
		send(exchange,
				new Reply(200, "{\"access_token\":\"" + access + "\",\"token_type\":\"Bearer\","
						+ "\"expires_in\":604800,\"refresh_token\":\"" + refresh + "\",\"scope\":\"identify guilds\"}",
						Map.of()));
	}

	/**
	 * Handles {@code /users/@me} and {@code /users/@me/guilds}.
	 *
	 * @param exchange the {@link HttpExchange}
	 * @throws IOException when the response fails
	 */
	private void users(HttpExchange exchange) throws IOException {
		var authorization = exchange.getRequestHeaders().getFirst("Authorization");
		if (authorization == null || !issuedTokens.contains(authorization.replaceFirst("^Bearer ", ""))) {
			send(exchange, new Reply(401, "{\"message\":\"401: Unauthorized\",\"code\":0}", Map.of()));
			return;
		}
		var path = exchange.getRequestURI().getPath();
		if (path.endsWith("/guilds")) {
			guildCalls.incrementAndGet();
			var queued = guildReplies.poll();
			send(exchange, queued != null ? queued : new Reply(200, guilds, Map.of()));
		} else {
			var queued = userReplies.poll();
			send(exchange, queued != null ? queued : new Reply(200, user, Map.of()));
		}
	}

	/**
	 * Builds Discord's {@code 429} reply.
	 *
	 * @param retryAfterSeconds the {@code retry_after} value
	 * @return the {@link Reply}
	 */
	private static Reply rateLimit(double retryAfterSeconds) {
		return new Reply(429,
				"{\"message\":\"You are being rate limited.\",\"retry_after\":" + retryAfterSeconds
						+ ",\"global\":false}",
				Map.of("Retry-After", Long.toString((long) Math.ceil(retryAfterSeconds))));
	}

	/**
	 * Sends a JSON reply.
	 *
	 * @param exchange the {@link HttpExchange}
	 * @param reply    the {@link Reply}
	 * @throws IOException when the response fails
	 */
	private static void send(HttpExchange exchange, Reply reply) throws IOException {
		var body = reply.body().getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		reply.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
		exchange.sendResponseHeaders(reply.status(), body.length);
		try (var out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

	/**
	 * A canned reply.
	 *
	 * @param status  the status code
	 * @param body    the JSON body
	 * @param headers extra headers
	 * @author mcawful
	 */
	private record Reply(int status, String body, Map<String, String> headers) {
	}
}
