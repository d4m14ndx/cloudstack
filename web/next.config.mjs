import createNextIntlPlugin from "next-intl/plugin";

/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  output: "standalone",
  // typedRoutes is disabled while Phase 5a uses `next dev --turbo`;
  // Next.js 14.2 rejects experimental.typedRoutes under Turbopack.
};

const withNextIntl = createNextIntlPlugin();

export default withNextIntl(nextConfig);
