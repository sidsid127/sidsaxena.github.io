import Image from "next/image";
import { createClient } from "@/lib/supabase/server";
import { uploadPhoto, deletePhoto } from "./actions";
import type { Photo } from "@/lib/supabase/types";

export default async function PhotosPage({
  params,
}: {
  params: Promise<{ restaurantId: string }>;
}) {
  const { restaurantId } = await params;
  const supabase = await createClient();

  const { data: photos, error } = await supabase
    .from("photos")
    .select("*")
    .eq("restaurant_id", restaurantId)
    .order("sort_order", { ascending: true });

  if (error) throw error;

  const rows = (photos ?? []) as Photo[];

  return (
    <div className="space-y-8">
      <form
        action={uploadPhoto.bind(null, restaurantId)}
        className="rounded-lg border border-neutral-200 bg-white p-5"
      >
        <h2 className="mb-3 text-sm font-medium text-neutral-900">Add a photo</h2>
        <div className="flex flex-wrap items-end gap-3">
          <input
            type="file"
            name="file"
            accept="image/jpeg,image/png,image/webp"
            required
            className="text-sm"
          />
          <input
            type="text"
            name="caption"
            placeholder="Caption (optional)"
            className="rounded-md border border-neutral-300 px-3 py-1.5 text-sm"
          />
          <button
            type="submit"
            className="rounded-md bg-neutral-900 px-3 py-1.5 text-sm font-medium text-white hover:bg-neutral-800"
          >
            Upload
          </button>
        </div>
        <p className="mt-2 text-xs text-neutral-400">JPEG, PNG or WebP, up to 8MB.</p>
      </form>

      <div>
        <h2 className="mb-3 text-sm font-medium text-neutral-900">Gallery</h2>
        {rows.length === 0 ? (
          <p className="text-sm text-neutral-500">No photos yet.</p>
        ) : (
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4">
            {rows.map((photo) => {
              const url =
                photo.source === "owner" && photo.storage_path
                  ? supabase.storage.from("restaurant-photos").getPublicUrl(photo.storage_path)
                      .data.publicUrl
                  : null;

              return (
                <div key={photo.id} className="group relative overflow-hidden rounded-lg border border-neutral-200 bg-white">
                  <div className="relative aspect-square bg-neutral-100">
                    {url ? (
                      <Image src={url} alt={photo.caption ?? ""} fill className="object-cover" />
                    ) : (
                      <div className="flex h-full items-center justify-center text-xs text-neutral-400">
                        Google photo
                      </div>
                    )}
                  </div>
                  <div className="flex items-center justify-between gap-2 px-2 py-1.5">
                    <span className="truncate text-xs text-neutral-500">
                      {photo.caption ?? (photo.source === "google" ? "From Google" : "")}
                    </span>
                    {photo.source === "owner" && photo.storage_path && (
                      <form
                        action={deletePhoto.bind(null, restaurantId, photo.id, photo.storage_path)}
                      >
                        <button className="text-xs text-red-600 underline">Delete</button>
                      </form>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
