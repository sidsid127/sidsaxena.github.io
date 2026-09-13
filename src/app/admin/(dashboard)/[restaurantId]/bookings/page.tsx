import { createClient } from "@/lib/supabase/server";
import { updateBookingStatus } from "./actions";
import type { Booking, BookingStatus } from "@/lib/supabase/types";

const STATUS_STYLES: Record<BookingStatus, string> = {
  pending: "bg-amber-100 text-amber-800",
  confirmed: "bg-emerald-100 text-emerald-800",
  declined: "bg-red-100 text-red-800",
  cancelled: "bg-neutral-100 text-neutral-500",
};

export default async function BookingsPage({
  params,
}: {
  params: Promise<{ restaurantId: string }>;
}) {
  const { restaurantId } = await params;
  const supabase = await createClient();

  const { data: bookings, error } = await supabase
    .from("bookings")
    .select("*")
    .eq("restaurant_id", restaurantId)
    .order("requested_date", { ascending: true })
    .order("requested_time", { ascending: true });

  if (error) throw error;

  const rows = (bookings ?? []) as Booking[];

  if (rows.length === 0) {
    return (
      <p className="rounded-lg border border-neutral-200 bg-white p-6 text-sm text-neutral-600">
        No booking requests yet. New requests from your site will show up here.
      </p>
    );
  }

  return (
    <div className="overflow-x-auto rounded-lg border border-neutral-200 bg-white">
      <table className="w-full text-left text-sm">
        <thead className="border-b border-neutral-200 bg-neutral-50 text-neutral-500">
          <tr>
            <th className="px-4 py-3 font-medium">Date &amp; time</th>
            <th className="px-4 py-3 font-medium">Guest</th>
            <th className="px-4 py-3 font-medium">Party</th>
            <th className="px-4 py-3 font-medium">Notes</th>
            <th className="px-4 py-3 font-medium">Status</th>
            <th className="px-4 py-3 font-medium">Actions</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-neutral-100">
          {rows.map((booking) => (
            <tr key={booking.id}>
              <td className="px-4 py-3 whitespace-nowrap">
                {booking.requested_date} · {booking.requested_time}
              </td>
              <td className="px-4 py-3">
                <div className="font-medium text-neutral-900">{booking.guest_name}</div>
                <div className="text-neutral-500">{booking.guest_phone}</div>
              </td>
              <td className="px-4 py-3">{booking.party_size}</td>
              <td className="max-w-[16rem] px-4 py-3 text-neutral-500">
                {booking.notes ?? "—"}
              </td>
              <td className="px-4 py-3">
                <span
                  className={`rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_STYLES[booking.status]}`}
                >
                  {booking.status}
                </span>
              </td>
              <td className="px-4 py-3">
                <div className="flex gap-2">
                  {booking.status !== "confirmed" && (
                    <form
                      action={updateBookingStatus.bind(null, restaurantId, booking.id, "confirmed")}
                    >
                      <button className="text-xs font-medium text-emerald-700 underline">
                        Confirm
                      </button>
                    </form>
                  )}
                  {booking.status !== "declined" && (
                    <form
                      action={updateBookingStatus.bind(null, restaurantId, booking.id, "declined")}
                    >
                      <button className="text-xs font-medium text-red-700 underline">
                        Decline
                      </button>
                    </form>
                  )}
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
