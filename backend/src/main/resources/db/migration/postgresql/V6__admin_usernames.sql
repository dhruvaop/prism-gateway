ALTER TABLE auth_users ADD COLUMN username TEXT;
CREATE UNIQUE INDEX auth_users_username_idx ON auth_users(username);
