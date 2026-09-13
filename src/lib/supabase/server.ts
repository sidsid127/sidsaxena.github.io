import { cookies } from "next/headers";
import { createServerClient } from "@supabase/ssr";

/**
 * Server-side Supabase client bound to the current request's cookies.
 * Create a fresh one per request — never module-scope this.
 *
 * Intentionally untyped (no Database generic): matching @supabase/ssr's
 * exact structural Database contract by hand is brittle busywork for an
 * MVP schema that's still moving. Once this project is linked to a real
 * Supabase project, run `npx supabase gen types typescript` and wire the
 * generated type back in here — see src/lib/supabase/types.ts.
 */
export async function createClient() {
  const cookieStore = await cookies();

  return createServerClient(
    process.env.NEXT_PUBLIC_SUPABASE_URL!,
    process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY!,
    {
      cookies: {
        getAll() {
          return cookieStore.getAll();
        },
        setAll(cookiesToSet) {
          try {
            cookiesToSet.forEach(({ name, value, options }) =>
              cookieStore.set(name, value, options),
            );
          } catch {
            // Called from a Server Component during render — the
            // middleware refreshes the session instead, so this is safe
            // to ignore.
          }
        },
      },
    },
  );
}
