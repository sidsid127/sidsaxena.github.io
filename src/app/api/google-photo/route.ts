import { NextResponse, type NextRequest } from "next/server";
import { resolvePhotoUrl } from "@/lib/google-places";

export const dynamic = "force-dynamic";

/**
 * Resolves a cached Google Places photo reference to Google's real image
 * URL and redirects the browser straight to it, so the API key used to
 * resolve it is never exposed client-side and image bytes never pass
 * through our server.
 */
export async function GET(request: NextRequest) {
  const ref = request.nextUrl.searchParams.get("ref");
  if (!ref) {
    return NextResponse.json({ error: "Missing ref" }, { status: 400 });
  }

  try {
    const url = await resolvePhotoUrl(ref);
    const response = NextResponse.redirect(url, 302);
    response.headers.set("Cache-Control", "public, max-age=86400, s-maxage=86400");
    return response;
  } catch {
    return NextResponse.json({ error: "Photo unavailable" }, { status: 502 });
  }
}
