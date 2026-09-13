import Link from "next/link";
import { requireUser, getManagedRestaurants } from "@/lib/auth";

export default async function AdminHomePage() {
  const { supabase, userId } = await requireUser();
  const managed = await getManagedRestaurants(supabase, userId);

  if (managed.length === 0) {
    return (
      <div className="rounded-lg border border-neutral-200 bg-white p-6 text-sm text-neutral-600">
        No restaurant is linked to your account yet. Ask us to finish setting up your
        listing and it will show up here.
      </div>
    );
  }

  return (
    <div>
      <h1 className="mb-6 text-lg font-semibold text-neutral-900">Your restaurants</h1>
      <ul className="grid gap-4 sm:grid-cols-2">
        {managed.map(({ restaurant, role }) => (
          <li
            key={restaurant.id}
            className="rounded-lg border border-neutral-200 bg-white p-5 shadow-sm"
          >
            <div className="mb-1 flex items-center justify-between">
              <h2 className="font-medium text-neutral-900">{restaurant.name}</h2>
              <span className="rounded-full bg-neutral-100 px-2 py-0.5 text-xs text-neutral-500">
                {role}
              </span>
            </div>
            <p className="mb-4 text-sm text-neutral-500">
              {restaurant.is_published ? "Live" : "Not published yet"} · /{restaurant.slug}
            </p>
            <div className="flex flex-wrap gap-3 text-sm">
              <Link className="text-neutral-900 underline" href={`/admin/${restaurant.id}/bookings`}>
                Bookings
              </Link>
              <Link className="text-neutral-900 underline" href={`/admin/${restaurant.id}/photos`}>
                Photos
              </Link>
              <Link className="text-neutral-900 underline" href={`/admin/${restaurant.id}/menu`}>
                Menu
              </Link>
              <Link className="text-neutral-900 underline" href={`/admin/${restaurant.id}/settings`}>
                Settings
              </Link>
              <Link className="text-neutral-500 underline" href={`/${restaurant.slug}`} target="_blank">
                View site ↗
              </Link>
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}
