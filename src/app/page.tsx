import Link from "next/link";

export default function Home() {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-4 bg-neutral-50 px-4 text-center">
      <h1 className="text-2xl font-semibold text-neutral-900">Restaurant Website Builder</h1>
      <p className="max-w-md text-neutral-600">
        Multi-tenant platform for restaurant websites — live menu, photos, Google reviews,
        and table booking requests, with an owner admin dashboard.
      </p>
      <Link
        href="/admin"
        className="rounded-md bg-neutral-900 px-4 py-2 text-sm font-medium text-white hover:bg-neutral-800"
      >
        Owner login →
      </Link>
    </div>
  );
}
