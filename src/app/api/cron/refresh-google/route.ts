import { NextResponse, type NextRequest } from "next/server";
import { createAdminClient } from "@/lib/supabase/admin";
import { fetchPlaceDetails } from "@/lib/google-places";

export const dynamic = "force-dynamic";
export const maxDuration = 60;

/**
 * Refreshes cached Google rating/reviews/photos for every published
 * restaurant that has a google_place_id set. Meant to run on a schedule
 * (see vercel.json) rather than per page view: keeps Places API cost
 * bounded and keeps cached content within Google's required refresh
 * window instead of caching it indefinitely.
 */
export async function GET(request: NextRequest) {
  const authHeader = request.headers.get("authorization");
  if (authHeader !== `Bearer ${process.env.CRON_SECRET}`) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  const supabase = createAdminClient();

  const { data: restaurants, error } = await supabase
    .from("restaurants")
    .select("id, google_place_id")
    .eq("is_published", true)
    .not("google_place_id", "is", null);

  if (error) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }

  const results: { restaurantId: string; ok: boolean; error?: string }[] = [];

  for (const restaurant of restaurants ?? []) {
    if (!restaurant.google_place_id) continue;

    try {
      const details = await fetchPlaceDetails(restaurant.google_place_id);

      await supabase
        .from("restaurants")
        .update({
          google_rating: details.rating ?? null,
          google_review_count: details.userRatingCount ?? null,
          google_last_synced_at: new Date().toISOString(),
        })
        .eq("id", restaurant.id);

      for (const review of details.reviews ?? []) {
        await supabase.from("google_reviews_cache").upsert(
          {
            restaurant_id: restaurant.id,
            google_review_id: review.name,
            author_name: review.authorAttribution?.displayName ?? null,
            author_photo_url: review.authorAttribution?.photoUri ?? null,
            rating: review.rating ?? null,
            review_text: review.text?.text ?? null,
            relative_time_description: review.relativePublishTimeDescription ?? null,
            review_time: review.publishTime ?? null,
            fetched_at: new Date().toISOString(),
          },
          { onConflict: "restaurant_id,google_review_id" },
        );
      }

      const { data: existingGooglePhotos } = await supabase
        .from("photos")
        .select("google_photo_ref")
        .eq("restaurant_id", restaurant.id)
        .eq("source", "google");
      const knownRefs = new Set((existingGooglePhotos ?? []).map((p) => p.google_photo_ref));

      const newPhotos = (details.photos ?? [])
        .filter((photo) => !knownRefs.has(photo.name))
        .slice(0, 10)
        .map((photo, index) => ({
          restaurant_id: restaurant.id,
          source: "google" as const,
          google_photo_ref: photo.name,
          sort_order: 100 + index, // after owner photos
        }));

      if (newPhotos.length > 0) {
        await supabase.from("photos").insert(newPhotos);
      }

      results.push({ restaurantId: restaurant.id, ok: true });
    } catch (err) {
      results.push({
        restaurantId: restaurant.id,
        ok: false,
        error: err instanceof Error ? err.message : "Unknown error",
      });
    }
  }

  return NextResponse.json({ synced: results.length, results });
}
