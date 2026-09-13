import type { ReactNode } from "react";
import Link from "next/link";
import { requireUser, requireRestaurantAccess } from "@/lib/auth";

export default async function RestaurantAdminLayout({
  children,
  params,
}: {
  children: ReactNode;
  params: Promise<{ restaurantId: string }>;
}) {
  const { restaurantId } = await params;
  const { supabase, userId } = await requireUser();
  const { restaurant } = await requireRestaurantAccess(supabase, userId, restaurantId);

  const tabs = [
    { href: `/admin/${restaurantId}/bookings`, label: "Bookings" },
    { href: `/admin/${restaurantId}/photos`, label: "Photos" },
    { href: `/admin/${restaurantId}/menu`, label: "Menu" },
    { href: `/admin/${restaurantId}/settings`, label: "Settings" },
  ];

  return (
    <div>
      <div className="mb-6">
        <Link href="/admin" className="text-sm text-neutral-500 hover:text-neutral-900">
          ← All restaurants
        </Link>
        <h1 className="mt-1 text-lg font-semibold text-neutral-900">{restaurant.name}</h1>
      </div>

      <nav className="mb-6 flex gap-1 border-b border-neutral-200">
        {tabs.map((tab) => (
          <Link
            key={tab.href}
            href={tab.href}
            className="rounded-t-md px-3 py-2 text-sm text-neutral-600 hover:bg-neutral-100 hover:text-neutral-900"
          >
            {tab.label}
          </Link>
        ))}
      </nav>

      {children}
    </div>
  );
}
