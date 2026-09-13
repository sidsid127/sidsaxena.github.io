"use server";

import { redirect } from "next/navigation";
import { requireUser, requireRestaurantAccess } from "@/lib/auth";
import { getStripe } from "@/lib/stripe";

/**
 * Starts a Stripe Checkout subscription for this restaurant. The
 * webhook (src/app/api/stripe/webhook/route.ts) is what actually
 * activates access on completion — this route only kicks off payment.
 */
export async function startSubscriptionCheckout(restaurantId: string) {
  const { supabase, userId } = await requireUser();
  const { restaurant } = await requireRestaurantAccess(supabase, userId, restaurantId);

  const priceId = process.env.STRIPE_PRICE_ID;
  if (!priceId) throw new Error("STRIPE_PRICE_ID is not configured");

  const appUrl = process.env.NEXT_PUBLIC_APP_URL ?? "http://localhost:3000";
  const stripe = getStripe();

  const { data: billing } = await supabase
    .from("restaurant_billing")
    .select("stripe_customer_id")
    .eq("restaurant_id", restaurantId)
    .maybeSingle();

  const session = await stripe.checkout.sessions.create({
    mode: "subscription",
    customer: billing?.stripe_customer_id ?? undefined,
    line_items: [{ price: priceId, quantity: 1 }],
    success_url: `${appUrl}/admin/${restaurantId}/settings?checkout=success`,
    cancel_url: `${appUrl}/admin/${restaurantId}/settings?checkout=cancelled`,
    client_reference_id: restaurantId,
    subscription_data: { metadata: { restaurant_id: restaurantId } },
    metadata: { restaurant_id: restaurant.id },
  });

  if (!session.url) throw new Error("Stripe did not return a checkout URL");
  redirect(session.url);
}

/** Sends the owner to Stripe's hosted portal to manage/cancel their subscription. */
export async function openBillingPortal(restaurantId: string) {
  const { supabase, userId } = await requireUser();
  await requireRestaurantAccess(supabase, userId, restaurantId);

  const { data: billing } = await supabase
    .from("restaurant_billing")
    .select("stripe_customer_id")
    .eq("restaurant_id", restaurantId)
    .maybeSingle();

  if (!billing?.stripe_customer_id) {
    throw new Error("No Stripe customer on file yet — subscribe first.");
  }

  const appUrl = process.env.NEXT_PUBLIC_APP_URL ?? "http://localhost:3000";
  const stripe = getStripe();
  const portalSession = await stripe.billingPortal.sessions.create({
    customer: billing.stripe_customer_id,
    return_url: `${appUrl}/admin/${restaurantId}/settings`,
  });

  redirect(portalSession.url);
}
