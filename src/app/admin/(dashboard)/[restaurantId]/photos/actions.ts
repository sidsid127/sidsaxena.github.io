"use server";

import { revalidatePath } from "next/cache";
import { createClient } from "@/lib/supabase/server";

const MAX_BYTES = 8 * 1024 * 1024; // 8MB
const ALLOWED_TYPES = new Set(["image/jpeg", "image/png", "image/webp"]);

export async function uploadPhoto(restaurantId: string, formData: FormData) {
  const file = formData.get("file");
  const caption = String(formData.get("caption") ?? "");

  if (!(file instanceof File) || file.size === 0) {
    throw new Error("Choose a photo to upload.");
  }
  if (!ALLOWED_TYPES.has(file.type)) {
    throw new Error("Photos must be JPEG, PNG, or WebP.");
  }
  if (file.size > MAX_BYTES) {
    throw new Error("Photo must be under 8MB.");
  }

  const supabase = await createClient();
  const ext = file.name.split(".").pop() ?? "jpg";
  const path = `${restaurantId}/${crypto.randomUUID()}.${ext}`;

  const { error: uploadError } = await supabase.storage
    .from("restaurant-photos")
    .upload(path, file, { contentType: file.type });

  if (uploadError) throw uploadError;

  const { error: insertError } = await supabase.from("photos").insert({
    restaurant_id: restaurantId,
    source: "owner",
    storage_path: path,
    caption: caption || null,
  });

  if (insertError) throw insertError;

  revalidatePath(`/admin/${restaurantId}/photos`);
}

export async function deletePhoto(restaurantId: string, photoId: string, storagePath: string) {
  const supabase = await createClient();

  const { error: storageError } = await supabase.storage
    .from("restaurant-photos")
    .remove([storagePath]);
  if (storageError) throw storageError;

  const { error: dbError } = await supabase
    .from("photos")
    .delete()
    .eq("id", photoId)
    .eq("restaurant_id", restaurantId);
  if (dbError) throw dbError;

  revalidatePath(`/admin/${restaurantId}/photos`);
}
