// Hand-written types matching supabase/migrations/0001_init.sql.
// Once the project is linked to a real Supabase project, regenerate with:
//   npx supabase gen types typescript --project-id <ref> > src/lib/supabase/types.ts

export type SubscriptionStatus =
  | "inactive"
  | "trialing"
  | "active"
  | "past_due"
  | "canceled";

export type BookingStatus = "pending" | "confirmed" | "declined" | "cancelled";

export interface Restaurant {
  id: string;
  slug: string;
  name: string;
  description: string | null;
  address: string | null;
  phone: string | null;
  notification_email: string | null;
  hours: Record<string, string> | null;
  google_place_id: string | null;
  google_rating: number | null;
  google_review_count: number | null;
  google_last_synced_at: string | null;
  is_published: boolean;
  created_at: string;
}

export interface RestaurantBilling {
  restaurant_id: string;
  stripe_customer_id: string | null;
  stripe_subscription_id: string | null;
  subscription_status: SubscriptionStatus;
  updated_at: string;
}

export interface MenuItem {
  id: string;
  restaurant_id: string;
  category: string;
  name: string;
  description: string | null;
  price_cents: number | null;
  is_available: boolean;
  sort_order: number;
  created_at: string;
}

export interface Photo {
  id: string;
  restaurant_id: string;
  source: "owner" | "google";
  storage_path: string | null;
  google_photo_ref: string | null;
  caption: string | null;
  sort_order: number;
  created_at: string;
}

export interface GoogleReview {
  id: string;
  restaurant_id: string;
  google_review_id: string;
  author_name: string | null;
  author_photo_url: string | null;
  rating: number | null;
  review_text: string | null;
  relative_time_description: string | null;
  review_time: string | null;
  fetched_at: string;
}

export interface Booking {
  id: string;
  restaurant_id: string;
  guest_name: string;
  guest_phone: string;
  guest_email: string | null;
  party_size: number;
  requested_date: string;
  requested_time: string;
  notes: string | null;
  status: BookingStatus;
  created_at: string;
}

// Minimal `Database` shape so `@supabase/ssr` clients stay type-safe
// without pulling in the full generated-types machinery for a hand
// maintained MVP schema.
// NOTE: not currently wired into the Supabase client generics (see
// src/lib/supabase/server.ts) — @supabase/ssr's exact structural
// Database contract shifts across versions and isn't worth hand-chasing
// for a schema this early. These interfaces are used to cast query
// results at the call site instead. Swap in `supabase gen types` output
// once the project is linked to a real Supabase project.
export interface Database {
  public: {
    Tables: {
      restaurants: { Row: Restaurant; Insert: Partial<Restaurant>; Update: Partial<Restaurant> };
      restaurant_billing: {
        Row: RestaurantBilling;
        Insert: Partial<RestaurantBilling>;
        Update: Partial<RestaurantBilling>;
      };
      restaurant_users: {
        Row: { restaurant_id: string; user_id: string; role: "owner" | "staff"; created_at: string };
        Insert: { restaurant_id: string; user_id: string; role?: "owner" | "staff" };
        Update: { role?: "owner" | "staff" };
      };
      menu_items: { Row: MenuItem; Insert: Partial<MenuItem>; Update: Partial<MenuItem> };
      photos: { Row: Photo; Insert: Partial<Photo>; Update: Partial<Photo> };
      google_reviews_cache: {
        Row: GoogleReview;
        Insert: Partial<GoogleReview>;
        Update: Partial<GoogleReview>;
      };
      bookings: { Row: Booking; Insert: Partial<Booking>; Update: Partial<Booking> };
    };
  };
}
