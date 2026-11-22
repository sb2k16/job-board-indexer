// The internal admin page's login: one password (ADMIN_PASSWORD, from Secrets Manager on AWS).
// A session is a cookie "<expiry>.<hmac>", signed with a key derived from the password, so
// changing the password ends every session. Pure functions; the cookie I/O is in lib/admin.ts.
import { createHash, createHmac, timingSafeEqual } from "node:crypto";

export const SESSION_COOKIE = "jbi_admin";
export const SESSION_SECONDS = 12 * 3600;

const key = (password: string) => createHash("sha256").update(`jbi-admin-session:${password}`).digest();
const sign = (password: string, exp: number) => createHmac("sha256", key(password)).update(String(exp)).digest("base64url");

function same(a: Buffer, b: Buffer): boolean {
  return a.length === b.length && timingSafeEqual(a, b);
}

export function passwordMatches(given: string, password: string): boolean {
  // Compare digests: equal length, so the comparison time says nothing about the password.
  return same(createHash("sha256").update(given).digest(), createHash("sha256").update(password).digest());
}

export function newSession(password: string, now = Date.now()): string {
  const exp = Math.floor(now / 1000) + SESSION_SECONDS;
  return `${exp}.${sign(password, exp)}`;
}

export function validSession(cookie: string | undefined, password: string, now = Date.now()): boolean {
  if (!cookie) return false;
  const m = /^(\d{1,12})\.([A-Za-z0-9_-]{43})$/.exec(cookie);
  if (!m) return false;
  const exp = Number(m[1]);
  if (exp * 1000 <= now) return false;
  return same(Buffer.from(m[2]), Buffer.from(sign(password, exp)));
}
