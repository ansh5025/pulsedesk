-- Optional contact email, used only for outbound delivery (never for login).
ALTER TABLE users ADD COLUMN email VARCHAR(254);
ALTER TABLE invitations ADD COLUMN email VARCHAR(254);
