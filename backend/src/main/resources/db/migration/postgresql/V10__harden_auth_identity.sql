-- Harden administrator identity.
--
-- Sign-in treats an email and a username as separate namespaces, so uniqueness
-- must be case-insensitive to match. Without this, "Admin@x.com" and
-- "admin@x.com" can both exist and a lookup becomes ambiguous.
--
-- This migration is deliberately fail-closed: if existing rows would collide it
-- stops and names the offending values instead of deleting an account.

DO $$
DECLARE
    duplicates TEXT;
BEGIN
    SELECT string_agg(format('%s x%s', lowered, total), ', ')
      INTO duplicates
      FROM (
        SELECT lower(btrim(email)) AS lowered, count(*) AS total
          FROM auth_users
         GROUP BY 1
        HAVING count(*) > 1
      ) grouped;

    IF duplicates IS NOT NULL THEN
        RAISE EXCEPTION 'auth_users has case-insensitive email duplicates: %', duplicates;
    END IF;

    SELECT string_agg(format('%s x%s', lowered, total), ', ')
      INTO duplicates
      FROM (
        SELECT lower(btrim(username)) AS lowered, count(*) AS total
          FROM auth_users
         WHERE username IS NOT NULL AND btrim(username) <> ''
         GROUP BY 1
        HAVING count(*) > 1
      ) grouped;

    IF duplicates IS NOT NULL THEN
        RAISE EXCEPTION 'auth_users has case-insensitive username duplicates: %', duplicates;
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
