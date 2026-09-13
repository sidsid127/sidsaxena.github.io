import "server-only";
import { createClient as createSupabaseClient } from "@supabase/supabase-js";

/**
 * Service-role Supabase client. Bypasses Row Level Security entirely —
 * only ever import this from trusted server code (cron jobs, webhooks),
 * never from anything reachable by a browser request on behalf of a
 * single tenant. Untyped for the same reason as the other clients — see
 * src/lib/supabase/server.ts.
 */
export function createAdminClient() {
  return createSupabaseClient(
    process.env.NEXT_PUBLIC_SUPABASE_URL!,
    process.env.SUPABASE_SERVICE_ROLE_KEY!,
    { auth: { autoRefreshToken: false, persistSession: false } },
  );
}
