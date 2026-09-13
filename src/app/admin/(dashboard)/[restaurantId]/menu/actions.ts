"use server";

import { revalidatePath } from "next/cache";
import { createClient } from "@/lib/supabase/server";

export async function addMenuItem(restaurantId: string, formData: FormData) {
  const name = String(formData.get("name") ?? "").trim();
  const category = String(formData.get("category") ?? "Menu").trim() || "Menu";
  const description = String(formData.get("description") ?? "").trim();
  const priceRaw = String(formData.get("price") ?? "").trim();

  if (!name) throw new Error("Item name is required.");

  const priceCents = priceRaw ? Math.round(parseFloat(priceRaw) * 100) : null;

  const supabase = await createClient();
  const { error } = await supabase.from("menu_items").insert({
    restaurant_id: restaurantId,
    name,
    category,
    description: description || null,
    price_cents: Number.isFinite(priceCents) ? priceCents : null,
  });

  if (error) throw error;
  revalidatePath(`/admin/${restaurantId}/menu`);
}

export async function toggleMenuItemAvailability(
  restaurantId: string,
  itemId: string,
  isAvailable: boolean,
) {
  const supabase = await createClient();
  const { error } = await supabase
    .from("menu_items")
    .update({ is_available: isAvailable })
    .eq("id", itemId)
    .eq("restaurant_id", restaurantId);

  if (error) throw error;
  revalidatePath(`/admin/${restaurantId}/menu`);
}

export async function deleteMenuItem(restaurantId: string, itemId: string) {
  const supabase = await createClient();
  const { error } = await supabase
    .from("menu_items")
    .delete()
    .eq("id", itemId)
    .eq("restaurant_id", restaurantId);

  if (error) throw error;
  revalidatePath(`/admin/${restaurantId}/menu`);
}
