import "server-only";
import { Resend } from "resend";
import type { Booking, Restaurant } from "@/lib/supabase/types";

function getClient() {
  if (!process.env.RESEND_API_KEY) return null;
  return new Resend(process.env.RESEND_API_KEY);
}

/**
 * Best-effort notification emails for a new booking request. Failures
 * here must never block the booking itself from being created — the
 * booking row in Postgres is the source of truth, email is a courtesy.
 */
export async function sendBookingEmails(restaurant: Restaurant, booking: Booking) {
  const resend = getClient();
  if (!resend) return;

  const from = process.env.RESEND_FROM_EMAIL ?? "bookings@example.com";
  const when = `${booking.requested_date} at ${booking.requested_time}`;

  const tasks: Promise<unknown>[] = [];

  if (booking.guest_email) {
    tasks.push(
      resend.emails.send({
        from,
        to: booking.guest_email,
        subject: `Booking request received — ${restaurant.name}`,
        text: `Thanks ${booking.guest_name}, we've sent your request for a table of ${booking.party_size} on ${when} to ${restaurant.name}. They'll confirm shortly.`,
      }),
    );
  }

  if (restaurant.notification_email) {
    tasks.push(
      resend.emails.send({
        from,
        to: restaurant.notification_email,
        subject: `New booking request — ${booking.guest_name} (party of ${booking.party_size})`,
        text: `${booking.guest_name} requested a table for ${booking.party_size} on ${when}.\nPhone: ${booking.guest_phone}\nNotes: ${booking.notes ?? "—"}\n\nConfirm or decline it from your admin dashboard.`,
      }),
    );
  }

  await Promise.allSettled(tasks);
}
