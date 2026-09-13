import { createClient } from "@/lib/supabase/server";
import { updateRestaurantSettings } from "./actions";
import { startSubscriptionCheckout, openBillingPortal } from "./billing-actions";

const BILLING_LABEL: Record<string, string> = {
  inactive: "No active subscription",
  trialing: "Trial active",
  active: "Active",
  past_due: "Payment past due",
  canceled: "Cancelled",
};

export default async function SettingsPage({
  params,
}: {
  params: Promise<{ restaurantId: string }>;
}) {
  const { restaurantId } = await params;
  const supabase = await createClient();

  const [{ data: restaurant, error }, { data: billing }] = await Promise.all([
    supabase.from("restaurants").select("*").eq("id", restaurantId).single(),
    supabase
      .from("restaurant_billing")
      .select("subscription_status")
      .eq("restaurant_id", restaurantId)
      .maybeSingle(),
  ]);

  if (error || !restaurant) throw error ?? new Error("Restaurant not found");

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-neutral-200 bg-white p-5 text-sm">
        <div>
          <span className="font-medium text-neutral-900">Subscription: </span>
          <span className="text-neutral-600">
            {BILLING_LABEL[billing?.subscription_status ?? "inactive"]}
          </span>
        </div>
        {billing?.subscription_status === "active" || billing?.subscription_status === "trialing" ? (
          <form action={openBillingPortal.bind(null, restaurantId)}>
            <button className="rounded-md border border-neutral-300 px-3 py-1.5 text-sm font-medium hover:bg-neutral-50">
              Manage subscription
            </button>
          </form>
        ) : (
          <form action={startSubscriptionCheckout.bind(null, restaurantId)}>
            <button className="rounded-md bg-neutral-900 px-3 py-1.5 text-sm font-medium text-white hover:bg-neutral-800">
              Subscribe
            </button>
          </form>
        )}
      </div>

      <form
        action={updateRestaurantSettings.bind(null, restaurantId)}
        className="space-y-4 rounded-lg border border-neutral-200 bg-white p-5"
      >
        <div>
          <label className="mb-1 block text-sm font-medium text-neutral-700">Name</label>
          <input
            name="name"
            defaultValue={restaurant.name}
            required
            className="w-full rounded-md border border-neutral-300 px-3 py-1.5 text-sm"
          />
        </div>

        <div>
          <label className="mb-1 block text-sm font-medium text-neutral-700">Description</label>
          <textarea
            name="description"
            defaultValue={restaurant.description ?? ""}
            rows={3}
            className="w-full rounded-md border border-neutral-300 px-3 py-1.5 text-sm"
          />
        </div>

        <div className="grid gap-4 sm:grid-cols-2">
          <div>
            <label className="mb-1 block text-sm font-medium text-neutral-700">Address</label>
            <input
              name="address"
              defaultValue={restaurant.address ?? ""}
              className="w-full rounded-md border border-neutral-300 px-3 py-1.5 text-sm"
            />
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium text-neutral-700">Phone</label>
            <input
              name="phone"
              defaultValue={restaurant.phone ?? ""}
              className="w-full rounded-md border border-neutral-300 px-3 py-1.5 text-sm"
            />
          </div>
        </div>

        <div>
          <label className="mb-1 block text-sm font-medium text-neutral-700">
            Booking notification email
          </label>
          <input
            name="notification_email"
            type="email"
            defaultValue={restaurant.notification_email ?? ""}
            placeholder="host@yourrestaurant.com"
            className="w-full rounded-md border border-neutral-300 px-3 py-1.5 text-sm"
          />
          <p className="mt-1 text-xs text-neutral-400">
            Where new booking requests are emailed. Can differ from your login email.
          </p>
        </div>

        <div>
          <label className="mb-1 block text-sm font-medium text-neutral-700">
            Hours (JSON)
          </label>
          <textarea
            name="hours"
            defaultValue={restaurant.hours ? JSON.stringify(restaurant.hours) : ""}
            placeholder='{"mon":"11:00-21:00","tue":"closed"}'
            rows={2}
            className="w-full rounded-md border border-neutral-300 px-3 py-1.5 font-mono text-xs"
          />
        </div>

        <div>
          <label className="mb-1 block text-sm font-medium text-neutral-700">
            Google Place ID
          </label>
          <input
            name="google_place_id"
            defaultValue={restaurant.google_place_id ?? ""}
            placeholder="ChIJ..."
            className="w-full rounded-md border border-neutral-300 px-3 py-1.5 text-sm"
          />
          <p className="mt-1 text-xs text-neutral-400">
            Powers the live Google rating, reviews, and photos on your public page. Find
            yours with Google&apos;s{" "}
            <a
              className="underline"
              href="https://developers.google.com/maps/documentation/places/web-service/place-id"
              target="_blank"
              rel="noreferrer"
            >
              Place ID finder
            </a>
            .
          </p>
        </div>

        <label className="flex items-center gap-2 text-sm text-neutral-700">
          <input type="checkbox" name="is_published" defaultChecked={restaurant.is_published} />
          Publish this site publicly
        </label>

        <button
          type="submit"
          className="rounded-md bg-neutral-900 px-4 py-2 text-sm font-medium text-white hover:bg-neutral-800"
        >
          Save changes
        </button>
      </form>
    </div>
  );
}
