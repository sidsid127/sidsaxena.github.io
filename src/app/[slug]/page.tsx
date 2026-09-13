import type { Metadata } from "next";
import Image from "next/image";
import { notFound } from "next/navigation";
import { createClient } from "@/lib/supabase/server";
import { BookingForm } from "./booking-form";
import type { MenuItem, Photo, GoogleReview, Restaurant } from "@/lib/supabase/types";

async function getRestaurant(slug: string) {
  const supabase = await createClient();
  const { data: restaurant } = await supabase
    .from("restaurants")
    .select("*")
    .eq("slug", slug)
    .eq("is_published", true)
    .single();

  if (!restaurant) return null;

  const [{ data: menuItems }, { data: photos }, { data: reviews }] = await Promise.all([
    supabase
      .from("menu_items")
      .select("*")
      .eq("restaurant_id", restaurant.id)
      .eq("is_available", true)
      .order("category", { ascending: true })
      .order("sort_order", { ascending: true }),
    supabase
      .from("photos")
      .select("*")
      .eq("restaurant_id", restaurant.id)
      .order("sort_order", { ascending: true }),
    supabase
      .from("google_reviews_cache")
      .select("*")
      .eq("restaurant_id", restaurant.id)
      .order("review_time", { ascending: false }),
  ]);

  return {
    restaurant: restaurant as Restaurant,
    menuItems: (menuItems ?? []) as MenuItem[],
    photos: (photos ?? []) as Photo[],
    reviews: (reviews ?? []) as GoogleReview[],
  };
}

export async function generateMetadata({
  params,
}: {
  params: Promise<{ slug: string }>;
}): Promise<Metadata> {
  const { slug } = await params;
  const data = await getRestaurant(slug);
  if (!data) return {};

  const { restaurant } = data;
  return {
    title: `${restaurant.name}${restaurant.address ? ` — ${restaurant.address}` : ""}`,
    description:
      restaurant.description ??
      `Menu, hours, photos, and table booking for ${restaurant.name}.`,
  };
}

const DAY_LABELS: Record<string, string> = {
  mon: "Monday",
  tue: "Tuesday",
  wed: "Wednesday",
  thu: "Thursday",
  fri: "Friday",
  sat: "Saturday",
  sun: "Sunday",
};

function formatPrice(cents: number | null) {
  if (cents == null) return "";
  return `$${(cents / 100).toFixed(2)}`;
}

export default async function RestaurantPage({
  params,
}: {
  params: Promise<{ slug: string }>;
}) {
  const { slug } = await params;
  const data = await getRestaurant(slug);
  if (!data) notFound();

  const { restaurant, menuItems, photos, reviews } = data;
  const supabase = await createClient();

  const menuByCategory = menuItems.reduce<Record<string, MenuItem[]>>((acc, item) => {
    (acc[item.category] ??= []).push(item);
    return acc;
  }, {});

  const jsonLd = {
    "@context": "https://schema.org",
    "@type": "Restaurant",
    name: restaurant.name,
    description: restaurant.description ?? undefined,
    address: restaurant.address ?? undefined,
    telephone: restaurant.phone ?? undefined,
    aggregateRating: restaurant.google_rating
      ? {
          "@type": "AggregateRating",
          ratingValue: restaurant.google_rating,
          reviewCount: restaurant.google_review_count ?? undefined,
        }
      : undefined,
  };

  return (
    <div className="min-h-screen bg-white">
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
      />

      <header className="border-b border-neutral-200 bg-neutral-50 px-4 py-10">
        <div className="mx-auto max-w-3xl">
          <h1 className="text-3xl font-semibold text-neutral-900">{restaurant.name}</h1>
          {restaurant.description && (
            <p className="mt-2 text-neutral-600">{restaurant.description}</p>
          )}
          <div className="mt-4 flex flex-wrap gap-x-6 gap-y-1 text-sm text-neutral-500">
            {restaurant.address && <span>{restaurant.address}</span>}
            {restaurant.phone && <span>{restaurant.phone}</span>}
            {restaurant.google_rating && (
              <span>
                ★ {restaurant.google_rating.toFixed(1)}
                {restaurant.google_review_count ? ` (${restaurant.google_review_count} Google reviews)` : ""}
              </span>
            )}
          </div>
        </div>
      </header>

      <main className="mx-auto max-w-3xl space-y-12 px-4 py-10">
        {photos.length > 0 && (
          <section>
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
              {photos.slice(0, 9).map((photo) => {
                const url =
                  photo.source === "owner" && photo.storage_path
                    ? supabase.storage.from("restaurant-photos").getPublicUrl(photo.storage_path)
                        .data.publicUrl
                    : photo.google_photo_ref
                      ? `/api/google-photo?ref=${encodeURIComponent(photo.google_photo_ref)}`
                      : null;
                if (!url) return null;
                return (
                  <div key={photo.id} className="relative aspect-square overflow-hidden rounded-lg bg-neutral-100">
                    <Image src={url} alt={photo.caption ?? restaurant.name} fill className="object-cover" />
                  </div>
                );
              })}
            </div>
          </section>
        )}

        {Object.keys(menuByCategory).length > 0 && (
          <section>
            <h2 className="mb-4 text-xl font-semibold text-neutral-900">Menu</h2>
            <div className="space-y-6">
              {Object.entries(menuByCategory).map(([category, items]) => (
                <div key={category}>
                  <h3 className="mb-2 text-sm font-medium tracking-wide text-neutral-500 uppercase">
                    {category}
                  </h3>
                  <ul className="divide-y divide-neutral-100">
                    {items.map((item) => (
                      <li key={item.id} className="flex items-start justify-between gap-4 py-2">
                        <div>
                          <p className="font-medium text-neutral-900">{item.name}</p>
                          {item.description && (
                            <p className="text-sm text-neutral-500">{item.description}</p>
                          )}
                        </div>
                        <p className="whitespace-nowrap text-sm text-neutral-700">
                          {formatPrice(item.price_cents)}
                        </p>
                      </li>
                    ))}
                  </ul>
                </div>
              ))}
            </div>
          </section>
        )}

        {restaurant.hours && (
          <section>
            <h2 className="mb-4 text-xl font-semibold text-neutral-900">Hours</h2>
            <ul className="grid max-w-xs grid-cols-2 gap-y-1 text-sm text-neutral-700">
              {Object.entries(restaurant.hours).map(([day, value]) => (
                <li key={day} className="contents">
                  <span className="text-neutral-500">{DAY_LABELS[day] ?? day}</span>
                  <span>{value}</span>
                </li>
              ))}
            </ul>
          </section>
        )}

        {reviews.length > 0 && (
          <section>
            <h2 className="mb-4 text-xl font-semibold text-neutral-900">What people are saying</h2>
            <div className="grid gap-4 sm:grid-cols-2">
              {reviews.slice(0, 6).map((review) => (
                <div key={review.id} className="rounded-lg border border-neutral-200 p-4">
                  <div className="mb-1 flex items-center justify-between">
                    <p className="text-sm font-medium text-neutral-900">
                      {review.author_name ?? "Google user"}
                    </p>
                    <p className="text-sm text-neutral-500">{"★".repeat(review.rating ?? 0)}</p>
                  </div>
                  {review.review_text && (
                    <p className="line-clamp-4 text-sm text-neutral-600">{review.review_text}</p>
                  )}
                  {review.relative_time_description && (
                    <p className="mt-2 text-xs text-neutral-400">
                      {review.relative_time_description} via Google
                    </p>
                  )}
                </div>
              ))}
            </div>
            <p className="mt-2 text-xs text-neutral-400">Reviews powered by Google.</p>
          </section>
        )}

        <section>
          <h2 className="mb-4 text-xl font-semibold text-neutral-900">Request a table</h2>
          <BookingForm restaurantId={restaurant.id} />
        </section>
      </main>
    </div>
  );
}
