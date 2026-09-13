# ShopScout

A pocket guide for shoppers: search a brand, a mall, or a city and get store
locations, mall floor directories, and directions — with an optional
"use my location" mode that sorts results by distance.

## Try it

Open `index.html` in a browser (or serve the folder with any static file
server — it's plain HTML/CSS/JS, no build step, no backend). It's ready to
publish as-is via GitHub Pages.

Things to try:
- Search a brand: `Apple`, `Superdry`, `Zara`
- Search a mall: `MyZeil`, `The Dubai Mall`, `Westfield London`
- Search a city: `Frankfurt`, `Dubai`, `London`
- Click **Use my location** to see the nearest malls sorted by distance

## How it's built

- `index.html` — page structure and search UI
- `assets/js/data.js` — the brand/store/mall dataset
- `assets/js/app.js` — search, geolocation/distance math, and all rendering
- `assets/css/style.css` — styling, including a dark-mode palette

Everything runs client-side against an in-memory dataset (`BRANDS`,
`MALLS` in `data.js`). Distances use the haversine formula against real
building coordinates. Maps use Google's keyless embed URL format
(`maps.google.com/maps?q=<lat>,<lng>&output=embed`) and the
`google.com/maps/dir/?api=1` deep link for directions — both work with no
API key, which is why the app runs with zero configuration.

## Wiring in real data

This prototype's mock dataset is the seam where a real backend would plug
in:

1. **Store locator feeds** — most large retailers (Apple, H&M, Zara, Nike…)
   publish a store-locator API or sitemap. A small scraper/sync job could
   populate `BRANDS` from these on a schedule.
2. **Mall directories** — mall operators (Unibail-Rodamco-Westfield, Emaar,
   ECE) publish tenant directories on their own sites; some expose them as
   JSON behind their store-finder widgets.
3. **Google Places API** — swap the mock geocoding for the Places API
   (`Text Search` / `Nearby Search`) to resolve "stores near me" against
   live business listings, and the Maps JavaScript API (with an API key)
   for interactive maps instead of the keyless iframe embed used here.
4. **Real floor plans** — the current floor view is a directory grid, not a
   literal blueprint. A next step would be layering the directory data onto
   an actual mall floor plan (SVG/image) with click-to-locate zones, which
   most large malls already provide as a wayfinding kiosk asset.
5. **Persistence** — move `data.js` into a small API (or a static JSON file
   fetched at runtime) once the dataset grows past what's comfortable to
   hand-maintain in a JS file.
