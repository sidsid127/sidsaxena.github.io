"use server";

import { revalidatePath } from "next/cache";
import { createClient } from "@/lib/supabase/server";
import type { BookingStatus } from "@/lib/supabase/types";

export async function updateBookingStatus(
  restaurantId: string,
  bookingId: string,
  status: BookingStatus,
) {
  const supabase = await createClient();

  // RLS (members manage their bookings) enforces that this only succeeds
  // for a user who belongs to restaurant_id — this filter just narrows
  // the row we intend to touch.
  const { error } = await supabase
    .from("bookings")
    .update({ status })
    .eq("id", bookingId)
    .eq("restaurant_id", restaurantId);

  if (error) throw error;

  revalidatePath(`/admin/${restaurantId}/bookings`);
}
