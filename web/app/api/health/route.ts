// Load balancer health check: the server is up. Deliberately does not query OpenSearch or Postgres,
// so a slow dependency degrades pages instead of getting healthy tasks replaced.
export const dynamic = "force-dynamic";

export function GET() {
  return Response.json({ ok: true });
}
