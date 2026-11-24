-- M1 accounts (plan B3). Discord IDs are 64-bit snowflakes, stored as bigint and sent to clients as strings.
-- No email column: we don't request the Discord email scope (plan B11, data minimization).

CREATE TABLE app_user (
	id              uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
	discord_id      bigint       NOT NULL,
	username        varchar(32)  NOT NULL,
	global_name     varchar(32),
	avatar_hash     varchar(64),
	mfa_enabled     boolean      NOT NULL DEFAULT false,
	-- IANA ID such as America/Chicago. NULL until the user picks one during onboarding.
	time_zone       varchar(64),
	push_enabled    boolean      NOT NULL DEFAULT false,
	suspended_until timestamptz,
	created_at      timestamptz  NOT NULL DEFAULT now(),
	last_login_at   timestamptz  NOT NULL DEFAULT now(),
	CONSTRAINT app_user_discord_id_key UNIQUE (discord_id)
);

-- The servers a user was in at their last login sync. A server makes the user discoverable only when they opt in.
CREATE TABLE user_guild (
	user_id      uuid         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
	guild_id     bigint       NOT NULL,
	guild_name   varchar(100) NOT NULL,
	icon_hash    varchar(64),
	discoverable boolean      NOT NULL DEFAULT false,
	synced_at    timestamptz  NOT NULL DEFAULT now(),
	-- The primary key's index leads with user_id, so it also serves "this user's servers" lookups; a separate
	-- user_guild(user_id) index would only duplicate it.
	CONSTRAINT user_guild_pkey PRIMARY KEY (user_id, guild_id)
);

-- "Who is discoverable in this server" (visibility and audience checks). Only opted-in rows are indexed, and user_id
-- is included so those checks can be answered from the index alone.
CREATE INDEX user_guild_discoverable_guild_idx ON user_guild (guild_id, user_id) WHERE discoverable;
