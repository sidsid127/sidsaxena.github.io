import { createClient } from "@/lib/supabase/server";
import { addMenuItem, toggleMenuItemAvailability, deleteMenuItem } from "./actions";
import type { MenuItem } from "@/lib/supabase/types";

function formatPrice(cents: number | null) {
  if (cents == null) return "";
  return `$${(cents / 100).toFixed(2)}`;
}

export default async function MenuPage({
  params,
}: {
  params: Promise<{ restaurantId: string }>;
}) {
  const { restaurantId } = await params;
  const supabase = await createClient();

  const { data: items, error } = await supabase
    .from("menu_items")
    .select("*")
    .eq("restaurant_id", restaurantId)
    .order("category", { ascending: true })
    .order("sort_order", { ascending: true });

  if (error) throw error;
  const rows = (items ?? []) as MenuItem[];

  return (
    <div className="space-y-8">
      <form
        action={addMenuItem.bind(null, restaurantId)}
        className="grid gap-3 rounded-lg border border-neutral-200 bg-white p-5 sm:grid-cols-2"
      >
        <h2 className="text-sm font-medium text-neutral-900 sm:col-span-2">Add a menu item</h2>
        <input
          name="name"
          placeholder="Item name"
          required
          className="rounded-md border border-neutral-300 px-3 py-1.5 text-sm"
        />
        <input
          name="category"
          placeholder="Category (e.g. Starters)"
          className="rounded-md border border-neutral-300 px-3 py-1.5 text-sm"
        />
        <input
          name="price"
          type="number"
          step="0.01"
          min="0"
          placeholder="Price"
          className="rounded-md border border-neutral-300 px-3 py-1.5 text-sm"
        />
        <input
          name="description"
          placeholder="Description (optional)"
          className="rounded-md border border-neutral-300 px-3 py-1.5 text-sm"
        />
        <button
          type="submit"
          className="rounded-md bg-neutral-900 px-3 py-1.5 text-sm font-medium text-white hover:bg-neutral-800 sm:col-span-2 sm:w-fit"
        >
          Add item
        </button>
      </form>

      {rows.length === 0 ? (
        <p className="text-sm text-neutral-500">No menu items yet.</p>
      ) : (
        <div className="overflow-hidden rounded-lg border border-neutral-200 bg-white">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-neutral-200 bg-neutral-50 text-neutral-500">
              <tr>
                <th className="px-4 py-3 font-medium">Item</th>
                <th className="px-4 py-3 font-medium">Category</th>
                <th className="px-4 py-3 font-medium">Price</th>
                <th className="px-4 py-3 font-medium">Available</th>
                <th className="px-4 py-3 font-medium"></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-neutral-100">
              {rows.map((item) => (
                <tr key={item.id}>
                  <td className="px-4 py-3">
                    <div className="font-medium text-neutral-900">{item.name}</div>
                    {item.description && (
                      <div className="text-neutral-500">{item.description}</div>
                    )}
                  </td>
                  <td className="px-4 py-3 text-neutral-500">{item.category}</td>
                  <td className="px-4 py-3 text-neutral-500">{formatPrice(item.price_cents)}</td>
                  <td className="px-4 py-3">
                    <form
                      action={toggleMenuItemAvailability.bind(
                        null,
                        restaurantId,
                        item.id,
                        !item.is_available,
                      )}
                    >
                      <button
                        className={`rounded-full px-2 py-0.5 text-xs font-medium ${
                          item.is_available
                            ? "bg-emerald-100 text-emerald-800"
                            : "bg-neutral-100 text-neutral-500"
                        }`}
                      >
                        {item.is_available ? "Available" : "86'd"}
                      </button>
                    </form>
                  </td>
                  <td className="px-4 py-3">
                    <form action={deleteMenuItem.bind(null, restaurantId, item.id)}>
                      <button className="text-xs text-red-600 underline">Remove</button>
                    </form>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
