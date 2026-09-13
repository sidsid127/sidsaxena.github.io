import type { ReactNode } from "react";
import Link from "next/link";
import { signOut } from "../login/actions";

export default function AdminLayout({ children }: { children: ReactNode }) {
  return (
    <div className="min-h-screen bg-neutral-50">
      <header className="border-b border-neutral-200 bg-white">
        <div className="mx-auto flex max-w-5xl items-center justify-between px-4 py-3">
          <Link href="/admin" className="text-sm font-semibold text-neutral-900">
            Restaurant Admin
          </Link>
          <form action={signOut}>
            <button type="submit" className="text-sm text-neutral-500 hover:text-neutral-900">
              Sign out
            </button>
          </form>
        </div>
      </header>
      <main className="mx-auto max-w-5xl px-4 py-8">{children}</main>
    </div>
  );
}
