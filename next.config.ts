import type { NextConfig } from "next";

const supabaseUrl = process.env.NEXT_PUBLIC_SUPABASE_URL;
const supabaseHostname = supabaseUrl ? new URL(supabaseUrl).hostname : undefined;

const nextConfig: NextConfig = {
  images: {
    remotePatterns: [
      // Owner-uploaded photos served from Supabase Storage.
      ...(supabaseHostname
        ? [{ protocol: "https" as const, hostname: supabaseHostname, pathname: "/storage/v1/object/public/**" }]
        : []),
      // Google-sourced photos are served through /api/google-photo (see
      // src/app/api/google-photo/route.ts) so the Places API key never
      // reaches the browser — no remote pattern needed for Google hosts.
    ],
  },
};

export default nextConfig;
