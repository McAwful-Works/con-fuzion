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
package app.confuzion.backend.controller;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.confuzion.backend.model.record.GuildListResponse;
import app.confuzion.backend.model.record.GuildResponse;
import app.confuzion.backend.model.record.MeResponse;
import app.confuzion.backend.model.record.ResponseRecord;
import app.confuzion.backend.model.record.UpdateGuildRequest;
import app.confuzion.backend.model.record.UpdateGuildsRequest;
import app.confuzion.backend.model.record.UpdateMeRequest;
import app.confuzion.backend.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

/**
 * The {@link RestController} for the signed-in user's own profile and Discord
 * servers. Every method acts on the session's user only; the user ID always
 * comes from the session, never from the request.
 *
 * @author mcawful
 */
@RestController
@RequestMapping("/api/me")
@Tag(name = "me", description = "The signed-in user's profile and Discord servers")
public class MeController {
	/**
	 * The {@link UserService}.
	 */
	private final UserService userService;

	/**
	 * Creates the controller.
	 *
	 * @param userService the {@link UserService}
	 */
	public MeController(UserService userService) {
		this.userService = userService;
	}

	/**
	 * Returns the signed-in user's profile.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @return the {@link MeResponse} envelope
	 */
	@GetMapping
	@Operation(summary = "Get the signed-in user's profile")
	public ResponseRecord<MeResponse> getMe(@Parameter(hidden = true) Authentication authentication) {
		return new ResponseRecord<>(userService.getMe(userId(authentication), authentication.getAuthorities()));
	}

	/**
	 * Updates the signed-in user's settings.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param request        the {@link UpdateMeRequest}
	 * @return the updated {@link MeResponse} envelope
	 */
	@PatchMapping
	@Operation(summary = "Update the signed-in user's time zone or push setting", description = "A field that is missing or null leaves that setting unchanged.")
	public ResponseRecord<MeResponse> updateMe(@Parameter(hidden = true) Authentication authentication,
			@Valid @RequestBody UpdateMeRequest request) {
		return new ResponseRecord<>(
				userService.updateMe(userId(authentication), authentication.getAuthorities(), request));
	}

	/**
	 * Returns the signed-in user's Discord servers as of their last login sync.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @return the {@link GuildListResponse} envelope
	 */
	@GetMapping("/guilds")
	@Operation(summary = "List the signed-in user's Discord servers", description = "As of the last login sync, sorted by name.")
	public ResponseRecord<GuildListResponse> getGuilds(@Parameter(hidden = true) Authentication authentication) {
		return new ResponseRecord<>(userService.getGuilds(userId(authentication)));
	}

	/**
	 * Sets whether the signed-in user is discoverable in several of their servers
	 * at once, all or nothing.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param request        the {@link UpdateGuildsRequest}
	 * @return the full, updated {@link GuildListResponse} envelope
	 */
	@PutMapping("/guilds")
	@Operation(summary = "Set whether the signed-in user is discoverable in several of their servers at once", description = "For onboarding's \"select all\". All or nothing, in one transaction, and one request "
			+ "against the rate limit. Returns the full updated server list.")
	// Declaring the 404 drops springdoc's inferred 200, so the 200 is declared too.
	@ApiResponse(responseCode = "200", description = "OK")
	@ApiResponse(responseCode = "404", description = "A guildId isn't one of the user's synced servers. Nothing is changed.")
	public ResponseRecord<GuildListResponse> updateGuilds(@Parameter(hidden = true) Authentication authentication,
			@Valid @RequestBody UpdateGuildsRequest request) {
		return new ResponseRecord<>(userService.updateGuilds(userId(authentication), request));
	}

	/**
	 * Sets whether the signed-in user is discoverable in one of their servers.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param guildId        the Discord server ID
	 * @param request        the {@link UpdateGuildRequest}
	 * @return the updated {@link GuildResponse} envelope
	 */
	@PutMapping("/guilds/{guildId}")
	@Operation(summary = "Set whether the signed-in user is discoverable in one of their servers", description = "404 when the server isn't one of the user's synced servers.")
	public ResponseRecord<GuildResponse> updateGuild(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "Discord server ID (a snowflake, as a string)") @PathVariable @Pattern(regexp = GuildResponse.SNOWFLAKE_PATTERN) String guildId,
			@Valid @RequestBody UpdateGuildRequest request) {
		return new ResponseRecord<>(userService.updateGuild(userId(authentication), guildId, request.discoverable()));
	}

	/**
	 * Returns the signed-in user's ID, which is the principal name.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @return the user ID
	 */
	private static UUID userId(Authentication authentication) {
		return UUID.fromString(authentication.getName());
	}
}
