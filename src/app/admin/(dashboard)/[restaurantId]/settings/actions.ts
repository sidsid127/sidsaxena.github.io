"use server";

import { revalidatePath } from "next/cache";
import { createClient } from "@/lib/supabase/server";

export async function updateRestaurantSettings(restaurantId: string, formData: FormData) {
  const name = String(formData.get("name") ?? "").trim();
  const description = String(formData.get("description") ?? "").trim();
  const address = String(formData.get("address") ?? "").trim();
  const phone = String(formData.get("phone") ?? "").trim();
  const notificationEmail = String(formData.get("notification_email") ?? "").trim();
  const googlePlaceId = String(formData.get("google_place_id") ?? "").trim();
  const isPublished = formData.get("is_published") === "on";
  const hoursRaw = String(formData.get("hours") ?? "").trim();

  if (!name) throw new Error("Restaurant name is required.");

  let hours: Record<string, string> | null = null;
  if (hoursRaw) {
    try {
      hours = JSON.parse(hoursRaw);
    } catch {
      throw new Error('Hours must be valid JSON, e.g. {"mon":"11:00-21:00","tue":"closed"}');
    }
  }

  const supabase = await createClient();
  const { error } = await supabase
    .from("restaurants")
    .update({
      name,
      description: description || null,
      address: address || null,
      phone: phone || null,
      notification_email: notificationEmail || null,
      google_place_id: googlePlaceId || null,
      is_published: isPublished,
      hours,
    })
    .eq("id", restaurantId);

  if (error) throw error;
  revalidatePath(`/admin/${restaurantId}/settings`);
  revalidatePath(`/admin`);
}
