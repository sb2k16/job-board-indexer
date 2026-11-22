/** @type {import('next').NextConfig} */
const nextConfig = {
  output: "standalone",
  poweredByHeader: false,
  // Crawl health lives at /health, open without a password for now (docs/ui-build-2026-11-19.md).
  async redirects() {
    return [
      { source: "/boards", destination: "/health", permanent: false },
      { source: "/admin", destination: "/health", permanent: false },
      { source: "/admin/:path*", destination: "/health", permanent: false },
    ];
  },
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
          { key: "X-Frame-Options", value: "DENY" },
        ],
      },
    ];
  },
};
export default nextConfig;
