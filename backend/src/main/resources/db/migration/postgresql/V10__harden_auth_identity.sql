-- Harden administrator identity.
--
-- Sign-in treats an email and a username as separate namespaces, so uniqueness
-- must be case-insensitive to match. Without this, "Admin@x.com" and
-- "admin@x.com" can both exist and a lookup becomes ambiguous.
--
-- This migration is deliberately fail-closed: if existing rows would collide it
-- stops with a count instead of deleting an account or printing email addresses
-- and usernames into migration logs.

DO $$
DECLARE
    duplicate_count INTEGER;
BEGIN
    SELECT count(*) INTO duplicate_count
      FROM (
        SELECT lower(btrim(email)) AS lowered
          FROM auth_users
         GROUP BY 1
        HAVING count(*) > 1
      ) grouped;

    IF duplicate_count > 0 THEN
        RAISE EXCEPTION 'auth_users has % case-insensitive email duplicate group(s); values are intentionally omitted from logs', duplicate_count;
    END IF;

    SELECT count(*) INTO duplicate_count
      FROM (
        SELECT lower(btrim(username)) AS lowered
          FROM auth_users
         WHERE username IS NOT NULL AND btrim(username) <> ''
         GROUP BY 1
        HAVING count(*) > 1
      ) grouped;

    IF duplicate_count > 0 THEN
        RAISE EXCEPTION 'auth_users has % case-insensitive username duplicate group(s); values are intentionally omitted from logs', duplicate_count;
    END IF;
END $$;

UPDATE auth_users
   SET email = lower(btrim(email))
 WHERE email <> lower(btrim(email));

UPDATE auth_users
   SET username = lower(btrim(username))
 WHERE username IS NOT NULL
   AND btrim(username) <> ''
   AND username <> lower(btrim(username));

-- These indexes make the uniqueness rule enforceable by the database rather
-- than by application code alone.
CREATE UNIQUE INDEX IF NOT EXISTS auth_users_email_lower_idx
    ON auth_users (lower(email));

CREATE UNIQUE INDEX IF NOT EXISTS auth_users_username_lower_idx
    ON auth_users (lower(username))
    WHERE username IS NOT NULL AND btrim(username) <> '';
