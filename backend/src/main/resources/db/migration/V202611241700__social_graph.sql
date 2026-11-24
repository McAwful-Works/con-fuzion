-- M2 social graph (plan B3, D-011): in-app friends made through invite links, circles, and blocks. Every user column
-- is our app_user UUID, never a Discord ID, and deleting a user deletes their rows.

-- One row per friendship, stored once with the smaller UUID first. Postgres orders uuid as unsigned bytes, which
-- Java's UUID.compareTo doesn't match, so order the pair in SQL (LEAST/GREATEST), never in Java. The primary key's
-- index leads with user_a, so user_b gets its own index for "this user's friends" lookups from the other side.
CREATE TABLE friendship (
	user_a     uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
	user_b     uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
	created_at timestamptz NOT NULL DEFAULT now(),
	CONSTRAINT friendship_pkey PRIMARY KEY (user_a, user_b),
	CONSTRAINT friendship_ordered CHECK (user_a < user_b)
);
CREATE INDEX friendship_user_b_idx ON friendship (user_b);

-- A single-use invite link. Only the SHA-256 of the 128-bit token is stored, so a database leak can't be turned into
-- working links. used_at and accepted_by are set together when someone accepts; accepted_by goes NULL if that
-- account is later deleted, and the invite stays used.
CREATE TABLE friend_invite (
	token_hash  bytea       PRIMARY KEY,
	inviter_id  uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
	created_at  timestamptz NOT NULL DEFAULT now(),
	expires_at  timestamptz NOT NULL,
	used_at     timestamptz,
	accepted_by uuid        REFERENCES app_user (id) ON DELETE SET NULL,
	CONSTRAINT friend_invite_token_hash_length CHECK (octet_length(token_hash) = 32),
	CONSTRAINT friend_invite_expiry CHECK (expires_at > created_at),
	CONSTRAINT friend_invite_accepted_is_used CHECK (accepted_by IS NULL OR used_at IS NOT NULL),
	CONSTRAINT friend_invite_not_own CHECK (accepted_by <> inviter_id)
);
-- Counting a user's unused invites for the 20-active cap.
CREATE INDEX friend_invite_inviter_unused_idx ON friend_invite (inviter_id, expires_at) WHERE used_at IS NULL;
-- Lets deleting an account find the invites it accepted without a full scan.
CREATE INDEX friend_invite_accepted_by_idx ON friend_invite (accepted_by) WHERE accepted_by IS NOT NULL;

-- A named group of people, private to its owner. URLs use public_id, so circle IDs can't be enumerated. The unique
-- index makes names case-insensitively unique per owner and also serves "this owner's circles" lookups.
CREATE TABLE circle (
	id         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	public_id  uuid        NOT NULL DEFAULT gen_random_uuid(),
	owner_id   uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
	name       varchar(50) NOT NULL,
	created_at timestamptz NOT NULL DEFAULT now(),
	CONSTRAINT circle_public_id_key UNIQUE (public_id),
	CONSTRAINT circle_name_not_blank CHECK (btrim(name) <> '')
);
CREATE UNIQUE INDEX circle_owner_name_key ON circle (owner_id, lower(name));

CREATE TABLE circle_member (
	circle_id bigint NOT NULL REFERENCES circle (id) ON DELETE CASCADE,
	user_id   uuid   NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
	CONSTRAINT circle_member_pkey PRIMARY KEY (circle_id, user_id)
);
-- "Which circles is this user in" (event visibility in M2, and removing members when someone blocks).
CREATE INDEX circle_member_user_idx ON circle_member (user_id);

-- A block cuts both people off from each other, whichever way round it was made (D-011).
CREATE TABLE block (
	blocker_id uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
	blocked_id uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
	created_at timestamptz NOT NULL DEFAULT now(),
	CONSTRAINT block_pkey PRIMARY KEY (blocker_id, blocked_id),
	CONSTRAINT block_not_self CHECK (blocker_id <> blocked_id)
);
CREATE INDEX block_blocked_idx ON block (blocked_id);
