import "server-only";

const FIELD_MASK = [
  "id",
  "rating",
  "userRatingCount",
  "reviews",
  "photos",
].join(",");

export interface PlaceReview {
  name: string; // resource name, unique per review — used as google_review_id
  relativePublishTimeDescription?: string;
  rating?: number;
  text?: { text?: string };
  authorAttribution?: { displayName?: string; photoUri?: string };
  publishTime?: string; // RFC3339
}

export interface PlacePhoto {
  name: string; // e.g. "places/PLACE_ID/photos/PHOTO_ID"
}

export interface PlaceDetails {
  rating?: number;
  userRatingCount?: number;
  reviews?: PlaceReview[];
  photos?: PlacePhoto[];
}

/**
 * Fetches the fields we cache from Google Places API (New) Place Details.
 * Called only from the scheduled refresh job — never on a public page
 * request — to keep both API cost and Google's cache-refresh terms in
 * check.
 */
export async function fetchPlaceDetails(placeId: string): Promise<PlaceDetails> {
  const apiKey = process.env.GOOGLE_PLACES_API_KEY;
  if (!apiKey) throw new Error("GOOGLE_PLACES_API_KEY is not configured");

  const res = await fetch(`https://places.googleapis.com/v1/places/${placeId}`, {
    headers: {
      "X-Goog-Api-Key": apiKey,
      "X-Goog-FieldMask": FIELD_MASK,
    },
    // Always hit Google fresh here; caching is handled at our own DB layer.
    cache: "no-store",
  });

  if (!res.ok) {
    throw new Error(`Places Details request failed (${res.status}): ${await res.text()}`);
  }

  return res.json();
}

/**
 * Resolves a Places photo resource name to Google's actual (unsigned,
 * key-free) CDN image URL by following the Photo Media endpoint's
 * redirect manually — the API key is used here, server-side, and never
 * reaches the browser.
 */
export async function resolvePhotoUrl(photoResourceName: string, maxWidthPx = 1200) {
  const apiKey = process.env.GOOGLE_PLACES_API_KEY;
  if (!apiKey) throw new Error("GOOGLE_PLACES_API_KEY is not configured");

  const res = await fetch(
    `https://places.googleapis.com/v1/${photoResourceName}/media?maxWidthPx=${maxWidthPx}&key=${apiKey}`,
    { redirect: "manual" },
  );

  const location = res.headers.get("location");
  if (!location) {
    throw new Error(`Places Photo request did not redirect (status ${res.status})`);
  }
  return location;
}
