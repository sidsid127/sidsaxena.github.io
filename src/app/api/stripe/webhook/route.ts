import { NextResponse, type NextRequest } from "next/server";
import type Stripe from "stripe";
import { getStripe } from "@/lib/stripe";
import { createAdminClient } from "@/lib/supabase/admin";
import type { SubscriptionStatus } from "@/lib/supabase/types";

export const dynamic = "force-dynamic";

function mapStripeStatus(status: Stripe.Subscription.Status): SubscriptionStatus {
  switch (status) {
    case "trialing":
      return "trialing";
    case "active":
      return "active";
    case "past_due":
    case "unpaid":
      return "past_due";
    case "canceled":
    case "incomplete_expired":
      return "canceled";
    default:
      return "inactive";
  }
}

export async function POST(request: NextRequest) {
  const signature = request.headers.get("stripe-signature");
  const webhookSecret = process.env.STRIPE_WEBHOOK_SECRET;

  if (!signature || !webhookSecret) {
    return NextResponse.json({ error: "Missing signature" }, { status: 400 });
  }

  const body = await request.text();
  const stripe = getStripe();

  let event: Stripe.Event;
  try {
    event = stripe.webhooks.constructEvent(body, signature, webhookSecret);
  } catch {
    return NextResponse.json({ error: "Invalid signature" }, { status: 400 });
  }

  const supabase = createAdminClient();

  switch (event.type) {
    case "checkout.session.completed": {
      const session = event.data.object as Stripe.Checkout.Session;
      const restaurantId = session.client_reference_id ?? session.metadata?.restaurant_id;
      if (restaurantId && session.customer) {
        await supabase.from("restaurant_billing").upsert(
          {
            restaurant_id: restaurantId,
            stripe_customer_id: String(session.customer),
            stripe_subscription_id: session.subscription ? String(session.subscription) : null,
            subscription_status: "active",
            updated_at: new Date().toISOString(),
          },
          { onConflict: "restaurant_id" },
        );
      }
      break;
    }

    case "customer.subscription.updated":
    case "customer.subscription.deleted": {
      const subscription = event.data.object as Stripe.Subscription;
      const restaurantId = subscription.metadata?.restaurant_id;
      if (restaurantId) {
        await supabase.from("restaurant_billing").upsert(
          {
            restaurant_id: restaurantId,
            stripe_customer_id: String(subscription.customer),
            stripe_subscription_id: subscription.id,
            subscription_status: mapStripeStatus(subscription.status),
            updated_at: new Date().toISOString(),
          },
          { onConflict: "restaurant_id" },
        );
      }
      break;
    }

    default:
      break;
  }

  return NextResponse.json({ received: true });
}
