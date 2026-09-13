-- Example: manually onboarding one pilot restaurant.
-- v1 has no self-serve signup — you create each pilot's tenant by hand:
--
-- 1. Supabase dashboard → Authentication → Users → Add user (invite the
--    owner by email, or set a temporary password and hand it to them).
-- 2. Copy that user's UUID.
-- 3. Fill in and run the block below (replace the placeholders).

with new_restaurant as (
  insert into restaurants (slug, name, description, address, phone, notification_email, is_published)
  values (
    'example-bistro',            -- becomes yourapp.com/example-bistro
    'Example Bistro',
    'Neighborhood Italian, family-owned since 1998.',
    '123 Main St, Your City',
    '+1 555-0100',
    'owner@example-bistro.com',
    false                         -- flip to true from /admin once the menu/photos are in
  )
  returning id
)
insert into restaurant_users (restaurant_id, user_id, role)
select id, '00000000-0000-0000-0000-000000000000'::uuid, 'owner'
from new_restaurant;
