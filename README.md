# Restaurant Website Builder

Multi-tenant platform for restaurant websites: a public page per
restaurant (menu, photos, live Google rating/reviews, table booking
requests) plus an owner admin dashboard (bookings inbox, photo upload,
menu editor, Google Place linking, Stripe subscription).

## Stack

- **Next.js 16** (App Router) — public pages + admin dashboard, one codebase
- **Supabase** — Postgres (with Row Level Security for tenant isolation), Auth (owner logins), Storage (uploaded photos)
- **Google Places API (New)** — rating/reviews/photos, pulled by a scheduled job, never live from the browser
- **Stripe Billing** — subscription only (restaurant owner pays you); no marketplace/online-ordering payments in v1
- **Resend** — booking confirmation + owner notification emails
- **Vercel** — hosting + Cron (the Google refresh job)

## Local setup

1. `npm install`
2. Create a Supabase project, then run the migrations in `supabase/migrations/`
   in order (SQL Editor, or `supabase db push` if you have the CLI linked).
3. Copy `.env.example` to `.env.local` and fill in the values (see comments
   in that file for where each key comes from).
4. `npm run dev` → http://localhost:3000

## Onboarding a restaurant (v1 is manual, by design)

There's no self-serve signup yet — see `supabase/seed.example.sql` for the
two-step flow: invite the owner as a Supabase Auth user, then insert their
`restaurants` row and link it via `restaurant_users`. Leave `is_published`
false until their menu/photos are in, then flip it from
Settings in the admin dashboard.

## Data model

- `restaurants` — public-safe tenant row (name, address, hours, Google Place ID, cached rating)
- `restaurant_users` — who can manage a restaurant (supports staff logins later, not just one owner)
- `restaurant_billing` — Stripe subscription state, **never** exposed to the anon role
- `menu_items`, `photos`, `bookings` — tenant-owned content
- `google_reviews_cache` — refreshed by `/api/cron/refresh-google`, the public page only ever reads this cache

Every tenant table is isolated by Postgres Row Level Security (see
`supabase/migrations/0001_init.sql`), not by remembering a `WHERE
restaurant_id = ...` in every query — a bug in application code can't leak
one restaurant's bookings to another.

## Google Places integration

The public page never calls Google directly. `/api/cron/refresh-google`
(scheduled daily via `vercel.json`) pulls rating/reviews/photos per
restaurant into Postgres; the page just reads that cache. Photos are
served through `/api/google-photo`, which resolves Google's photo
resource name to its real (key-free) image URL server-side and redirects
the browser to it — the API key never reaches the client.

Google's terms cap you at ~5 reviews per place (Google picks which) and
require attribution ("via Google" is shown on each review card) — this
isn't a full review-import tool, it's a live rating/reviews widget.

## Payments

Two very different things, only one of which is built:

- **You billing the restaurant** (Stripe Billing subscription) — built.
  See `billing-actions.ts` under the settings route and
  `/api/stripe/webhook`.
- **Diners paying the restaurant** (online ordering, deposits) — **not
  built**. Would require Stripe Connect (marketplace payments, per-restaurant
  KYC) and is a substantially bigger scope than v1's request-only booking.

## Bookings

v1 is request-only: a diner submits a request, the restaurant confirms or
declines from `/admin/[restaurantId]/bookings`. There's no live
availability/capacity engine — that's a deliberate simplification, not an
oversight; see the settings page's "Booking notification email" for how
the restaurant hears about a new request.

## Deploying

1. Push this repo, import it into Vercel.
2. Add every variable from `.env.example` in Vercel's Project Settings → Environment Variables.
3. Point a Stripe webhook at `https://<your-domain>/api/stripe/webhook` for
   `checkout.session.completed`, `customer.subscription.updated`, and
   `customer.subscription.deleted`; copy its signing secret into `STRIPE_WEBHOOK_SECRET`.
4. Vercel Cron picks up `vercel.json` automatically — confirm
   `CRON_SECRET` matches what's configured in the Cron Jobs dashboard.

### Known v1 limitation

Supabase's free tier pauses a project after ~7 days with no traffic — fine
for local development, but budget for the paid tier ($25/mo) once a real
pilot restaurant depends on the booking form being up.
