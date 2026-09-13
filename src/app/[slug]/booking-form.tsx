"use client";

import { useActionState } from "react";
import { requestBooking, type BookingFormState } from "./actions";

const initialState: BookingFormState = { error: null, success: false };

export function BookingForm({ restaurantId }: { restaurantId: string }) {
  const action = requestBooking.bind(null, restaurantId);
  const [state, formAction, pending] = useActionState(action, initialState);

  if (state.success) {
    return (
      <div className="rounded-lg border border-emerald-200 bg-emerald-50 p-5 text-sm text-emerald-800">
        Thanks — your request is in. The restaurant will reach out to confirm.
      </div>
    );
  }

  return (
    <form action={formAction} className="grid gap-3 sm:grid-cols-2">
      {/* Honeypot field, hidden from real visitors via CSS, not `hidden`, so basic bots that skip hidden inputs still fill it in */}
      <input
        type="text"
        name="company"
        tabIndex={-1}
        autoComplete="off"
        className="absolute -left-[9999px] h-0 w-0 opacity-0"
        aria-hidden="true"
      />

      <input
        name="guest_name"
        placeholder="Your name"
        required
        className="rounded-md border border-neutral-300 px-3 py-2 text-sm"
      />
      <input
        name="guest_phone"
        type="tel"
        placeholder="Phone"
        required
        className="rounded-md border border-neutral-300 px-3 py-2 text-sm"
      />
      <input
        name="guest_email"
        type="email"
        placeholder="Email (optional)"
        className="rounded-md border border-neutral-300 px-3 py-2 text-sm"
      />
      <input
        name="party_size"
        type="number"
        min={1}
        max={30}
        placeholder="Party size"
        required
        className="rounded-md border border-neutral-300 px-3 py-2 text-sm"
      />
      <input
        name="requested_date"
        type="date"
        required
        className="rounded-md border border-neutral-300 px-3 py-2 text-sm"
      />
      <input
        name="requested_time"
        type="time"
        required
        className="rounded-md border border-neutral-300 px-3 py-2 text-sm"
      />
      <textarea
        name="notes"
        placeholder="Anything we should know? (optional)"
        rows={2}
        className="rounded-md border border-neutral-300 px-3 py-2 text-sm sm:col-span-2"
      />

      {state.error && <p className="text-sm text-red-600 sm:col-span-2">{state.error}</p>}

      <button
        type="submit"
        disabled={pending}
        className="rounded-md bg-neutral-900 px-4 py-2 text-sm font-medium text-white hover:bg-neutral-800 disabled:opacity-50 sm:col-span-2 sm:w-fit"
      >
        {pending ? "Sending…" : "Request a table"}
      </button>
      <p className="text-xs text-neutral-400 sm:col-span-2">
        This sends a request — the restaurant confirms availability directly.
      </p>
    </form>
  );
}
