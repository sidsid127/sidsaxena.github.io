import { createBrowserClient } from "@supabase/ssr";

// See src/lib/supabase/server.ts for why this client is untyped for now.
export function createClient() {
  return createBrowserClient(
    process.env.NEXT_PUBLIC_SUPABASE_URL!,
    process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY!,
  );
}
