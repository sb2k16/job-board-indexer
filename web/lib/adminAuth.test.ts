import { test } from "node:test";
import assert from "node:assert/strict";
import { newSession, passwordMatches, validSession, SESSION_SECONDS } from "./adminAuth.ts";

test("a session signed with the password is valid until it expires", () => {
  const now = Date.parse("2026-11-19T00:00:00Z");
  const s = newSession("pw", now);
  assert.equal(validSession(s, "pw", now), true);
  assert.equal(validSession(s, "pw", now + (SESSION_SECONDS - 1) * 1000), true);
  assert.equal(validSession(s, "pw", now + SESSION_SECONDS * 1000), false);
});

test("a changed password, a forged expiry or garbage is refused", () => {
  const now = Date.now();
  const s = newSession("pw", now);
  assert.equal(validSession(s, "other", now), false);
  const [, sig] = s.split(".");
  assert.equal(validSession(`${Math.floor(now / 1000) + 10 * SESSION_SECONDS}.${sig}`, "pw", now), false);
  for (const bad of [undefined, "", "x", "1.2", `${s}x`, "9999999999." + "a".repeat(43)]) {
    assert.equal(validSession(bad, "pw", now), false, String(bad));
  }
});

test("password comparison", () => {
  assert.equal(passwordMatches("secret", "secret"), true);
  assert.equal(passwordMatches("secret ", "secret"), false);
  assert.equal(passwordMatches("", "secret"), false);
});
