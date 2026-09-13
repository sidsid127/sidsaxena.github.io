"use server";

import { createClient } from "@/lib/supabase/server";
import { sendBookingEmails } from "@/lib/email";
import type { Restaurant } from "@/lib/supabase/types";

export type BookingFormState = { error: string | null; success: boolean };

export async function requestBooking(
  restaurantId: string,
  _prevState: BookingFormState,
  formData: FormData,
): Promise<BookingFormState> {
  // Honeypot: real visitors never fill this hidden field in, bots often do.
  if (String(formData.get("company") ?? "").length > 0) {
    return { error: null, success: true };
  }

  const guestName = String(formData.get("guest_name") ?? "").trim();
  const guestPhone = String(formData.get("guest_phone") ?? "").trim();
  const guestEmail = String(formData.get("guest_email") ?? "").trim();
  const partySize = parseInt(String(formData.get("party_size") ?? ""), 10);
  const requestedDate = String(formData.get("requested_date") ?? "");
  const requestedTime = String(formData.get("requested_time") ?? "");
  const notes = String(formData.get("notes") ?? "").trim();

  if (!guestName || !guestPhone || !requestedDate || !requestedTime || !partySize) {
    return { error: "Please fill in your name, phone, party size, date, and time.", success: false };
  }
  if (partySize < 1 || partySize > 30) {
    return { error: "Party size must be between 1 and 30.", success: false };
  }

  const supabase = await createClient();

  const { data: booking, error } = await supabase
    .from("bookings")
    .insert({
      restaurant_id: restaurantId,
      guest_name: guestName,
      guest_phone: guestPhone,
      guest_email: guestEmail || null,
      party_size: partySize,
      requested_date: requestedDate,
      requested_time: requestedTime,
      notes: notes || null,
    })
    .select("*")
    .single();

  if (error || !booking) {
    return { error: "Something went wrong submitting your request. Please try again.", success: false };
  }

  const { data: restaurant } = await supabase
    .from("restaurants")
    .select("*")
    .eq("id", restaurantId)
    .single();

  if (restaurant) {
    await sendBookingEmails(restaurant as Restaurant, booking);
  }

  return { error: null, success: true };
}
