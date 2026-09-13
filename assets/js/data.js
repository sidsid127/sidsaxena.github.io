// Mock data standing in for a real backend (Google Places API, mall directory
// feeds, or partner data). Coordinates are the real building locations so the
// map embeds and distance math behave like a live dataset would.

const MALLS = [
  {
    id: "myzeil",
    name: "MyZeil",
    city: "Frankfurt am Main",
    country: "Germany",
    address: "Zeil 106, 60313 Frankfurt am Main, Germany",
    lat: 50.114,
    lng: 8.6821,
    floors: ["Ground Floor", "Level 1", "Level 2", "Level 3"],
  },
  {
    id: "dubai-mall",
    name: "The Dubai Mall",
    city: "Dubai",
    country: "United Arab Emirates",
    address: "Financial Center Rd, Downtown Dubai, Dubai, UAE",
    lat: 25.1972,
    lng: 55.2795,
    floors: ["Ground Floor", "Level 1", "Level 2"],
  },
  {
    id: "westfield-london",
    name: "Westfield London",
    city: "London",
    country: "United Kingdom",
    address: "Ariel Way, Shepherd's Bush, London W12 7GF, UK",
    lat: 51.5074,
    lng: -0.2216,
    floors: ["Ground Floor", "Level 1", "Level 2"],
  },
];

// Each brand lists every store it runs across the malls above.
const BRANDS = [
  {
    id: "apple",
    name: "Apple",
    category: "Electronics",
    icon: "\u{1F34E}",
    stores: [
      { mall: "myzeil", floor: "Ground Floor", zone: "A-12", hours: "10:00 – 20:00" },
      { mall: "dubai-mall", floor: "Level 1", zone: "SF-08", hours: "10:00 – 00:00" },
      { mall: "westfield-london", floor: "Ground Floor", zone: "G-22", hours: "10:00 – 21:00" },
    ],
  },
  {
    id: "superdry",
    name: "Superdry",
    category: "Fashion",
    icon: "\u{1F455}",
    stores: [
      { mall: "myzeil", floor: "Level 1", zone: "B-04", hours: "10:00 – 20:00" },
      { mall: "westfield-london", floor: "Level 1", zone: "L1-14", hours: "10:00 – 21:00" },
    ],
  },
  {
    id: "zara",
    name: "Zara",
    category: "Fashion",
    icon: "\u{1F457}",
    stores: [
      { mall: "myzeil", floor: "Ground Floor", zone: "A-02", hours: "10:00 – 20:00" },
      { mall: "dubai-mall", floor: "Ground Floor", zone: "GF-15", hours: "10:00 – 00:00" },
      { mall: "westfield-london", floor: "Ground Floor", zone: "G-05", hours: "10:00 – 21:00" },
    ],
  },
  {
    id: "hm",
    name: "H&M",
    category: "Fashion",
    icon: "\u{1F9E5}",
    stores: [
      { mall: "myzeil", floor: "Level 1", zone: "B-11", hours: "10:00 – 20:00" },
      { mall: "dubai-mall", floor: "Level 1", zone: "SF-22", hours: "10:00 – 00:00" },
    ],
  },
  {
    id: "nike",
    name: "Nike",
    category: "Footwear",
    icon: "\u{1F45F}",
    stores: [
      { mall: "myzeil", floor: "Level 2", zone: "C-09", hours: "10:00 – 20:00" },
      { mall: "dubai-mall", floor: "Ground Floor", zone: "GF-30", hours: "10:00 – 00:00" },
      { mall: "westfield-london", floor: "Level 1", zone: "L1-02", hours: "10:00 – 21:00" },
    ],
  },
  {
    id: "adidas",
    name: "Adidas",
    category: "Footwear",
    icon: "⚽",
    stores: [
      { mall: "dubai-mall", floor: "Ground Floor", zone: "GF-31", hours: "10:00 – 00:00" },
      { mall: "westfield-london", floor: "Level 1", zone: "L1-03", hours: "10:00 – 21:00" },
    ],
  },
  {
    id: "sephora",
    name: "Sephora",
    category: "Beauty",
    icon: "\u{1F484}",
    stores: [
      { mall: "myzeil", floor: "Ground Floor", zone: "A-18", hours: "10:00 – 20:00" },
      { mall: "dubai-mall", floor: "Ground Floor", zone: "GF-40", hours: "10:00 – 00:00" },
    ],
  },
  {
    id: "uniqlo",
    name: "Uniqlo",
    category: "Fashion",
    icon: "\u{1F455}",
    stores: [
      { mall: "westfield-london", floor: "Level 1", zone: "L1-20", hours: "10:00 – 21:00" },
      { mall: "dubai-mall", floor: "Level 1", zone: "SF-12", hours: "10:00 – 00:00" },
    ],
  },
  {
    id: "starbucks",
    name: "Starbucks",
    category: "Food & Drink",
    icon: "☕",
    stores: [
      { mall: "myzeil", floor: "Level 3", zone: "D-01", hours: "09:00 – 20:00" },
      { mall: "dubai-mall", floor: "Ground Floor", zone: "GF-02", hours: "09:00 – 00:00" },
      { mall: "westfield-london", floor: "Level 2", zone: "L2-01", hours: "08:00 – 21:00" },
    ],
  },
  {
    id: "shake-shack",
    name: "Shake Shack",
    category: "Food & Drink",
    icon: "\u{1F354}",
    stores: [
      { mall: "dubai-mall", floor: "Level 2", zone: "L2-10", hours: "10:00 – 00:00" },
      { mall: "westfield-london", floor: "Level 2", zone: "L2-06", hours: "11:00 – 22:00" },
    ],
  },
  {
    id: "lindt",
    name: "Lindt Chocolate",
    category: "Food & Drink",
    icon: "\u{1F36B}",
    stores: [
      { mall: "myzeil", floor: "Ground Floor", zone: "A-07", hours: "10:00 – 20:00" },
      { mall: "westfield-london", floor: "Ground Floor", zone: "G-11", hours: "10:00 – 21:00" },
    ],
  },
];

function getMall(id) {
  return MALLS.find((m) => m.id === id);
}

function getBrand(id) {
  return BRANDS.find((b) => b.id === id);
}

// Flattened list of every store, each tagged with its own brand and mall.
function allStores() {
  const stores = [];
  for (const brand of BRANDS) {
    for (const store of brand.stores) {
      const mall = getMall(store.mall);
      stores.push({ ...store, brand, mall });
    }
  }
  return stores;
}

function storesForMall(mallId) {
  return allStores().filter((s) => s.mall.id === mallId);
}
