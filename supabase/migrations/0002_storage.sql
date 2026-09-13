-- Storage bucket for owner-uploaded food photos.
-- Path convention: <restaurant_id>/<uuid>.<ext>  — the first path segment
-- is the tenant id, which the policies below check against
-- restaurant_users so one restaurant can never write into another's
-- folder.

insert into storage.buckets (id, name, public)
values ('restaurant-photos', 'restaurant-photos', true)
on conflict (id) do nothing;

create policy "restaurant photos are publicly readable"
  on storage.objects for select
  using (bucket_id = 'restaurant-photos');

create policy "members upload to their own restaurant folder"
  on storage.objects for insert
  with check (
    bucket_id = 'restaurant-photos'
    and is_restaurant_member(( (storage.foldername(name))[1] )::uuid)
  );

create policy "members manage files in their own restaurant folder"
  on storage.objects for update
  using (
    bucket_id = 'restaurant-photos'
    and is_restaurant_member(( (storage.foldername(name))[1] )::uuid)
  );

create policy "members delete files in their own restaurant folder"
  on storage.objects for delete
  using (
    bucket_id = 'restaurant-photos'
    and is_restaurant_member(( (storage.foldername(name))[1] )::uuid)
  );
