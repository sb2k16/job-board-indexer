// Server-side session checks for /admin. Every admin page calls requireAdmin() itself (no layout
// or middleware check), so a page can never render without it.
import "server-only";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { SESSION_COOKIE, validSession } from "./adminAuth";

/** The admin password, or null when the admin page is switched off (not configured). */
export function adminPassword(): string | null {
  const p = process.env.ADMIN_PASSWORD;
  return p && p.length >= 12 ? p : null;
}

export async function requireAdmin(): Promise<void> {
  const password = adminPassword();
  if (!password) redirect("/admin/login");
  const jar = await cookies();
  if (!validSession(jar.get(SESSION_COOKIE)?.value, password)) redirect("/admin/login");
}
