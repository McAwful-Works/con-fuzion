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

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.confuzion.backend.exception.InvalidInputException;
import app.confuzion.backend.model.entity.AppUser;
import app.confuzion.backend.model.entity.UserGuild;
import app.confuzion.backend.model.record.DiscordGuild;
import app.confuzion.backend.model.record.DiscordUser;
import app.confuzion.backend.model.record.GuildListResponse;
import app.confuzion.backend.model.record.GuildResponse;
import app.confuzion.backend.model.record.MeResponse;
import app.confuzion.backend.model.record.MeResponse.Role;
import app.confuzion.backend.model.record.UpdateGuildsRequest;
import app.confuzion.backend.model.record.UpdateMeRequest;
import app.confuzion.backend.repository.AppUserRepository;
import app.confuzion.backend.repository.UserGuildRepository;
import jakarta.persistence.EntityManager;

/**
 * The signed-in user's account and Discord servers. Every method acts only on
 * the user ID it is given, which callers take from the session, so a user can
 * only ever read or change their own rows. A server that isn't one of the
 * caller's is a 404, whether or not it exists for someone else.
 *
 * @author mcawful
 */
@Service
public class UserService {
	/**
	 * The Discord CDN base URL.
	 */
	private static final String CDN = "https://cdn.discordapp.com/";

	/**
	 * The number of default avatars Discord picks from for the new username system.
	 */
	private static final int DEFAULT_AVATARS = 6;

	/**
	 * The {@link AppUserRepository}.
	 */
	private final AppUserRepository appUserRepository;

	/**
	 * The {@link UserGuildRepository}.
	 */
	private final UserGuildRepository userGuildRepository;

	/**
	 * The {@link EntityManager}, to insert newly synced servers without a lookup
	 * per row.
	 */
	private final EntityManager entityManager;

	/**
	 * Creates the service.
	 *
	 * @param appUserRepository   the {@link AppUserRepository}
	 * @param userGuildRepository the {@link UserGuildRepository}
	 * @param entityManager       the {@link EntityManager}
	 */
	public UserService(AppUserRepository appUserRepository, UserGuildRepository userGuildRepository,
			EntityManager entityManager) {
		this.appUserRepository = appUserRepository;
		this.userGuildRepository = userGuildRepository;
		this.entityManager = entityManager;
	}

	/**
	 * Records a login: creates or refreshes the user, then replaces their server
	 * list with the one Discord just sent. New servers start not discoverable,
	 * known ones get their new name and icon (their {@code discoverable} choice is
	 * kept), and servers the user has left are deleted.
	 *
	 * @param user   the Discord user, already validated
	 * @param guilds the user's servers, or {@code null} to keep the last known list
	 *               because Discord didn't return one
	 * @return the user ID and whether this was the first login
	 */
	@Transactional
	public LoginResult recordLogin(DiscordUser user, List<DiscordGuild> guilds) {
		var row = appUserRepository.upsertFromDiscord(Long.parseLong(user.id()), user.username(), user.globalName(),
				user.avatar(), Boolean.TRUE.equals(user.mfaEnabled()));
		if (guilds != null)
			syncGuilds(row.getId(), guilds);
		return new LoginResult(row.getId(), Boolean.TRUE.equals(row.getInserted()));
	}

	/**
	 * Returns the user's profile.
	 *
	 * @param userId      the signed-in user's ID
	 * @param authorities the session's authorities, which decide the roles
	 * @return the {@link MeResponse}
	 * @throws NoSuchElementException when the user no longer exists
	 */
	@Transactional(readOnly = true)
	public MeResponse getMe(UUID userId, Collection<? extends GrantedAuthority> authorities) {
		return toMeResponse(appUserRepository.findById(userId).orElseThrow(), authorities);
	}

	/**
	 * Changes the settings the request sets, leaving the others as they are.
	 *
	 * @param userId      the signed-in user's ID
	 * @param authorities the session's authorities
	 * @param request     the validated {@link UpdateMeRequest}
	 * @return the updated {@link MeResponse}
	 * @throws NoSuchElementException when the user no longer exists
	 */
	@Transactional
	public MeResponse updateMe(UUID userId, Collection<? extends GrantedAuthority> authorities,
			UpdateMeRequest request) {
		var user = appUserRepository.findById(userId).orElseThrow();
		if (request.timeZone() != null)
			user.setTimeZone(request.timeZone());
		if (request.pushEnabled() != null)
			user.setPushEnabled(request.pushEnabled());
		return toMeResponse(user, authorities);
	}

	/**
	 * Returns the user's servers as of their last login sync, sorted by name.
	 *
	 * @param userId the signed-in user's ID
	 * @return the {@link GuildListResponse}
	 */
	@Transactional(readOnly = true)
	public GuildListResponse getGuilds(UUID userId) {
		return new GuildListResponse(userGuildRepository.findByUserIdOrderByGuildNameAscGuildIdAsc(userId).stream()
				.map(UserService::toGuildResponse).toList());
	}

	/**
	 * Sets whether the user is discoverable in one of their servers.
	 *
	 * @param userId       the signed-in user's ID
	 * @param guildId      the server ID from the path, already matching the
	 *                     snowflake pattern
	 * @param discoverable the new setting
	 * @return the updated {@link GuildResponse}
	 * @throws InvalidInputException  when the ID doesn't fit in a {@code bigint}
	 * @throws NoSuchElementException when the server isn't one of the user's
	 */
	@Transactional
	public GuildResponse updateGuild(UUID userId, String guildId, boolean discoverable) {
		var guild = userGuildRepository.findById(new UserGuild.Key(userId, parseSnowflake(guildId))).orElseThrow();
		guild.setDiscoverable(discoverable);
		return toGuildResponse(guild);
	}

	/**
	 * Sets whether the user is discoverable in several of their servers, all or
	 * nothing: if any server isn't one of the user's, nothing changes.
	 *
	 * @param userId  the signed-in user's ID
	 * @param request the validated {@link UpdateGuildsRequest}, whose IDs are
	 *                unique
	 * @return the full, updated {@link GuildListResponse}
	 * @throws InvalidInputException  when an ID doesn't fit in a {@code bigint}
	 * @throws NoSuchElementException when a server isn't one of the user's
	 */
	@Transactional
	public GuildListResponse updateGuilds(UUID userId, UpdateGuildsRequest request) {
		var changes = new HashMap<UserGuild.Key, Boolean>();
		for (var update : request.guilds())
			changes.put(new UserGuild.Key(userId, parseSnowflake(update.guildId())), update.discoverable());
		var guilds = userGuildRepository.findAllById(changes.keySet());
		if (guilds.size() != changes.size())
			throw new NoSuchElementException();
		for (var guild : guilds)
			guild.setDiscoverable(changes.get(new UserGuild.Key(userId, guild.getGuildId())));
		return getGuilds(userId);
	}

	/**
	 * Replaces the user's server list with Discord's.
	 *
	 * @param userId the user ID
	 * @param guilds the servers Discord returned, already validated
	 */
	private void syncGuilds(UUID userId, List<DiscordGuild> guilds) {
		var now = Instant.now();
		var fetched = new HashMap<Long, DiscordGuild>();
		for (var guild : guilds)
			fetched.put(Long.parseLong(guild.id()), guild);
		for (var known : userGuildRepository.findByUserIdOrderByGuildNameAscGuildIdAsc(userId)) {
			var guild = fetched.remove(known.getGuildId());
			if (guild == null) {
				userGuildRepository.delete(known);
			} else {
				known.setGuildName(guild.name());
				known.setIconHash(guild.icon());
				known.setSyncedAt(now);
			}
		}
		fetched.forEach((guildId, guild) -> entityManager
				.persist(new UserGuild(userId, guildId, guild.name(), guild.icon(), now)));
	}

	/**
	 * Parses a server ID that already matches the snowflake pattern.
	 *
	 * @param snowflake the ID
	 * @return the ID as a {@code long}
	 * @throws InvalidInputException when it is above {@link Long#MAX_VALUE}
	 */
	private static long parseSnowflake(String snowflake) {
		try {
			return Long.parseLong(snowflake);
		} catch (NumberFormatException e) {
			throw new InvalidInputException(e);
		}
	}

	/**
	 * Returns a user's Discord CDN avatar URL. A user without an avatar gets
	 * Discord's default one, picked by {@code (user_id >> 22) % 6}.
	 *
	 * @param discordId  the Discord user ID
	 * @param avatarHash the avatar hash, or {@code null}
	 * @return the avatar URL
	 */
	public static String avatarUrl(long discordId, String avatarHash) {
		return avatarHash == null ? CDN + "embed/avatars/" + (discordId >>> 22) % DEFAULT_AVATARS + ".png"
				: CDN + "avatars/" + discordId + "/" + avatarHash + ".png";
	}

	/**
	 * Maps a user to the {@link MeResponse}.
	 *
	 * @param user        the {@link AppUser}
	 * @param authorities the session's authorities
	 * @return the {@link MeResponse}
	 */
	private static MeResponse toMeResponse(AppUser user, Collection<? extends GrantedAuthority> authorities) {
		return new MeResponse(user.getId(), user.getUsername(), user.getGlobalName(),
				avatarUrl(user.getDiscordId(), user.getAvatarHash()), user.getTimeZone(), user.isPushEnabled(),
				roles(authorities), user.isMfaEnabled());
	}

	/**
	 * Returns the roles the session's authorities grant. {@link Role#USER} is
	 * always included.
	 *
	 * @param authorities the session's authorities
	 * @return the roles, in declaration order
	 */
	private static List<Role> roles(Collection<? extends GrantedAuthority> authorities) {
		var granted = authorities.stream().map(GrantedAuthority::getAuthority).toList();
		return Arrays.stream(Role.values()).filter(role -> role == Role.USER || granted.contains("ROLE_" + role.name()))
				.toList();
	}

	/**
	 * Maps a server to the {@link GuildResponse}.
	 *
	 * @param guild the {@link UserGuild}
	 * @return the {@link GuildResponse}
	 */
	private static GuildResponse toGuildResponse(UserGuild guild) {
		var iconUrl = guild.getIconHash() == null ? null
				: CDN + "icons/" + guild.getGuildId() + "/" + guild.getIconHash() + ".png";
		return new GuildResponse(Long.toString(guild.getGuildId()), guild.getGuildName(), iconUrl,
				guild.isDiscoverable());
	}

	/**
	 * The outcome of {@link UserService#recordLogin}.
	 *
	 * @param userId     the user ID
	 * @param firstLogin whether the user was just created
	 * @author mcawful
	 */
	public record LoginResult(UUID userId, boolean firstLogin) {
	}
}
