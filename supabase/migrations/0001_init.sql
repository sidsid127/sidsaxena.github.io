-- Restaurant Website Builder — initial schema
-- Multi-tenant: every tenant-owned table carries restaurant_id and is
-- isolated by Row Level Security, not by application-code convention.

create extension if not exists "pgcrypto";

-- ---------------------------------------------------------------------
-- restaurants: one row per tenant. Public-safe columns only — billing
-- fields live in restaurant_billing so the anon key can never see them.
-- ---------------------------------------------------------------------
create table restaurants (
  id uuid primary key default gen_random_uuid(),
  slug text unique not null check (slug ~ '^[a-z0-9-]+$'),
  name text not null,
  description text,
  address text,
  phone text,
  notification_email text, -- where new-booking alerts are sent; may differ from the owner's login email
  hours jsonb, -- { "mon": "11:00-21:00", "tue": "closed", ... }
  google_place_id text,
  google_rating numeric(2, 1),
  google_review_count integer,
  google_last_synced_at timestamptz,
  is_published boolean not null default false,
  created_at timestamptz not null default now()
);

-- restaurant_users: who can manage a restaurant. Separate from a plain
-- owner_user_id column so a restaurant can have more than one staff
-- login later without a schema change.
create table restaurant_users (
  restaurant_id uuid not null references restaurants (id) on delete cascade,
  user_id uuid not null references auth.users (id) on delete cascade,
  role text not null default 'owner' check (role in ('owner', 'staff')),
  created_at timestamptz not null default now(),
  primary key (restaurant_id, user_id)
);

-- restaurant_billing: Stripe subscription state. Never exposed to the
-- anon/public role — only the owning user and the service role (webhook
-- handler) can touch it.
create table restaurant_billing (
  restaurant_id uuid primary key references restaurants (id) on delete cascade,
  stripe_customer_id text,
  stripe_subscription_id text,
  subscription_status text not null default 'inactive'
    check (subscription_status in ('inactive', 'trialing', 'active', 'past_due', 'canceled')),
  updated_at timestamptz not null default now()
);

create table menu_items (
  id uuid primary key default gen_random_uuid(),
  restaurant_id uuid not null references restaurants (id) on delete cascade,
  category text not null default 'Menu',
  name text not null,
  description text,
  price_cents integer,
  is_available boolean not null default true,
  sort_order integer not null default 0,
  created_at timestamptz not null default now()
);

-- photos: both owner-uploaded (storage_path set, in the "restaurant-photos"
-- bucket) and Google-sourced (google_photo_ref set) rows live here so the
-- public page renders one unified gallery.
create table photos (
  id uuid primary key default gen_random_uuid(),
  restaurant_id uuid not null references restaurants (id) on delete cascade,
  source text not null check (source in ('owner', 'google')),
  storage_path text,
  google_photo_ref text,
  caption text,
  sort_order integer not null default 0,
  created_at timestamptz not null default now(),
  constraint photo_source_ref check (
    (source = 'owner' and storage_path is not null) or
    (source = 'google' and google_photo_ref is not null)
  )
);

-- google_reviews_cache: refreshed periodically by a scheduled job using
-- the service role key. The public page only ever reads this cache, it
-- never calls the Google Places API directly (keeps API cost bounded and
-- respects Google's cache-refresh terms).
create table google_reviews_cache (
  id uuid primary key default gen_random_uuid(),
  restaurant_id uuid not null references restaurants (id) on delete cascade,
  google_review_id text not null,
  author_name text,
  author_photo_url text,
  rating smallint check (rating between 1 and 5),
  review_text text,
  relative_time_description text,
  review_time timestamptz,
  fetched_at timestamptz not null default now(),
  unique (restaurant_id, google_review_id)
);

create table bookings (
  id uuid primary key default gen_random_uuid(),
  restaurant_id uuid not null references restaurants (id) on delete cascade,
  guest_name text not null,
  guest_phone text not null,
  guest_email text,
  party_size integer not null check (party_size > 0),
  requested_date date not null,
  requested_time time not null,
  notes text,
  status text not null default 'pending'
    check (status in ('pending', 'confirmed', 'declined', 'cancelled')),
  created_at timestamptz not null default now()
);

create index bookings_restaurant_id_idx on bookings (restaurant_id, requested_date);
create index menu_items_restaurant_id_idx on menu_items (restaurant_id);
create index photos_restaurant_id_idx on photos (restaurant_id);
create index google_reviews_cache_restaurant_id_idx on google_reviews_cache (restaurant_id);

-- ---------------------------------------------------------------------
-- Helper: is the current auth user a member (owner/staff) of this
-- restaurant? SECURITY DEFINER so it can read restaurant_users even
-- under a caller whose own RLS would otherwise block that read.
-- ---------------------------------------------------------------------
create or replace function is_restaurant_member(target_restaurant_id uuid)
returns boolean
language sql
security definer
set search_path = public
stable
as $$
  select exists (
    select 1 from restaurant_users
    where restaurant_id = target_restaurant_id
      and user_id = auth.uid()
  );
$$;

-- ---------------------------------------------------------------------
-- Row Level Security
-- ---------------------------------------------------------------------
alter table restaurants enable row level security;
alter table restaurant_users enable row level security;
alter table restaurant_billing enable row level security;
alter table menu_items enable row level security;
alter table photos enable row level security;
alter table google_reviews_cache enable row level security;
alter table bookings enable row level security;

-- restaurants: public can read published restaurants; members can read
-- and update their own (including unpublished, for editing before launch).
create policy "restaurants are publicly readable when published"
  on restaurants for select
  using (is_published or is_restaurant_member(id));

create policy "members can update their restaurant"
  on restaurants for update
  using (is_restaurant_member(id));

-- restaurant creation and deletion happen via the service role (during
-- manual/onboarding flow), not directly from client roles.

-- restaurant_users: members can see their own membership rows only.
create policy "members can view their own membership"
  on restaurant_users for select
  using (user_id = auth.uid());

-- restaurant_billing: only members of the restaurant may read its own
-- billing row; writes happen only via the Stripe webhook (service role).
create policy "members can view their restaurant's billing"
  on restaurant_billing for select
  using (is_restaurant_member(restaurant_id));

-- menu_items: public read for published restaurants, members manage.
create policy "menu items are publicly readable"
  on menu_items for select
  using (exists (
    select 1 from restaurants r
    where r.id = menu_items.restaurant_id
      and (r.is_published or is_restaurant_member(r.id))
  ));

create policy "members manage their menu items"
  on menu_items for all
  using (is_restaurant_member(restaurant_id))
  with check (is_restaurant_member(restaurant_id));

-- photos: public read for published restaurants, members manage owner-
-- sourced photos (google-sourced rows are written by the service role).
create policy "photos are publicly readable"
  on photos for select
  using (exists (
    select 1 from restaurants r
    where r.id = photos.restaurant_id
      and (r.is_published or is_restaurant_member(r.id))
  ));

create policy "members manage their own photos"
  on photos for all
  using (is_restaurant_member(restaurant_id) and source = 'owner')
  with check (is_restaurant_member(restaurant_id) and source = 'owner');

-- google_reviews_cache: public read only, writes are service-role-only
-- (the scheduled refresh job), so no insert/update/delete policy exists
-- for authenticated/anon roles.
create policy "google reviews are publicly readable"
  on google_reviews_cache for select
  using (exists (
    select 1 from restaurants r
    where r.id = google_reviews_cache.restaurant_id
      and (r.is_published or is_restaurant_member(r.id))
  ));

-- bookings: anyone can create a booking request for a published
-- restaurant; only that restaurant's members can read/update/cancel it.
create policy "anyone can request a booking"
  on bookings for insert
  with check (exists (
    select 1 from restaurants r
    where r.id = bookings.restaurant_id and r.is_published
  ));

create policy "members manage their bookings"
  on bookings for select
  using (is_restaurant_member(restaurant_id));

create policy "members update their bookings"
  on bookings for update
  using (is_restaurant_member(restaurant_id))
  with check (is_restaurant_member(restaurant_id));
