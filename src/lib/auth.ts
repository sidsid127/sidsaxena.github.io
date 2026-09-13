import "server-only";
import { redirect } from "next/navigation";
import { createClient } from "@/lib/supabase/server";

/** Redirects to /admin/login if there is no signed-in user. */
export async function requireUser() {
  const supabase = await createClient();
  const { data, error } = await supabase.auth.getClaims();

  if (error || !data?.claims) {
    redirect("/admin/login");
  }

  return { supabase, userId: data.claims.sub as string };
}

/**
 * Loads the restaurants the signed-in user manages. RLS on
 * restaurant_users already scopes this to the caller's own rows, but we
 * still join through restaurants explicitly rather than trusting a
 * restaurant_id passed in from the client.
 */
export async function getManagedRestaurants(
  supabase: Awaited<ReturnType<typeof createClient>>,
  userId: string,
) {
  const { data: memberships, error: membershipError } = await supabase
    .from("restaurant_users")
    .select("restaurant_id, role")
    .eq("user_id", userId);

  if (membershipError) throw membershipError;
  if (!memberships?.length) return [];

  const { data: restaurants, error: restaurantsError } = await supabase
    .from("restaurants")
    .select("*")
    .in(
      "id",
      memberships.map((m) => m.restaurant_id),
    );

  if (restaurantsError) throw restaurantsError;

  const roleByRestaurantId = new Map(memberships.map((m) => [m.restaurant_id, m.role]));

  return (restaurants ?? []).map((restaurant) => ({
    restaurant,
    role: roleByRestaurantId.get(restaurant.id) ?? "staff",
  }));
}

/**
 * Loads one restaurant by id and 404s (via redirect) if the signed-in
 * user isn't a member. RLS already blocks the underlying select, but
 * checking explicitly gives a clear redirect instead of a confusing
 * "not found" from a bare RLS-filtered empty result.
 */
export async function requireRestaurantAccess(
  supabase: Awaited<ReturnType<typeof createClient>>,
  userId: string,
  restaurantId: string,
) {
  const { data: membership } = await supabase
    .from("restaurant_users")
    .select("role")
    .eq("user_id", userId)
    .eq("restaurant_id", restaurantId)
    .maybeSingle();

  if (!membership) {
    redirect("/admin");
  }

  const { data: restaurant, error } = await supabase
    .from("restaurants")
    .select("*")
    .eq("id", restaurantId)
    .single();

  if (error || !restaurant) {
    redirect("/admin");
  }

  return { restaurant, role: membership.role };
}
