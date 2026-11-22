"use server";
// Sign in and out of /admin. Server actions: Next.js checks that a post comes from this site's own
// origin. They return instead of calling redirect(): behind a proxy, Next.js resolves an action's
// redirect against the address the server binds (0.0.0.0), not the host the visitor used, so the
// client components (AdminForms.tsx) move the browser with a relative URL.
import { cookies, headers } from "next/headers";
import { adminPassword } from "@/lib/admin";
import { newSession, passwordMatches, SESSION_COOKIE, SESSION_SECONDS } from "@/lib/adminAuth";

// Failed sign-ins per client address, in this task's memory: 10 per 15 minutes, then refused.
const WINDOW_MS = 15 * 60_000;
const MAX_FAILURES = 10;
const failures = new Map<string, number[]>();

async function client(): Promise<{ ip: string; secure: boolean }> {
  const h = await headers();
  // Behind the load balancer the last X-Forwarded-For entry is the address it saw.
  const xff = h.get("x-forwarded-for");
  return { ip: xff ? xff.split(",").at(-1)!.trim() : "local", secure: h.get("x-forwarded-proto") === "https" };
}

function recent(ip: string, now: number): number[] {
  const list = (failures.get(ip) ?? []).filter((t) => now - t < WINDOW_MS);
  if (list.length) failures.set(ip, list);
  else failures.delete(ip);
  return list;
}

export type SignInResult = { ok: true } | { ok: false; error: string } | null;

export async function signIn(_prev: SignInResult, form: FormData): Promise<SignInResult> {
  const password = adminPassword();
  if (!password) return { ok: false, error: "The admin pages are switched off." };
  const { ip, secure } = await client();
  const now = Date.now();
  if (recent(ip, now).length >= MAX_FAILURES) return { ok: false, error: "Too many wrong passwords. Try again in 15 minutes." };

  const given = String(form.get("password") ?? "").slice(0, 512);
  if (!passwordMatches(given, password)) {
    failures.set(ip, [...recent(ip, now), now]);
    await new Promise((r) => setTimeout(r, 1000)); // slows guessing; the window above caps it
    return { ok: false, error: "That password is not right." };
  }
  failures.delete(ip);
  (await cookies()).set(SESSION_COOKIE, newSession(password, now), {
    path: "/admin",
    maxAge: SESSION_SECONDS,
    httpOnly: true,
    sameSite: "strict",
    secure,
  });
  return { ok: true };
}

export async function signOut(): Promise<void> {
  (await cookies()).set(SESSION_COOKIE, "", { path: "/admin", maxAge: 0, httpOnly: true, sameSite: "strict" });
}
