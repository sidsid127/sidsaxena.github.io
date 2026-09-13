const searchInput = document.getElementById("search-input");
const searchForm = document.getElementById("search-form");
const suggestionsBox = document.getElementById("suggestions");
const resultsSection = document.getElementById("results");
const locateBtn = document.getElementById("locate-btn");
const locateStatus = document.getElementById("locate-status");

let userLocation = null;

function haversineKm(lat1, lng1, lat2, lng2) {
  const toRad = (d) => (d * Math.PI) / 180;
  const R = 6371;
  const dLat = toRad(lat2 - lat1);
  const dLng = toRad(lng2 - lng1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function distanceLabel(lat, lng) {
  if (!userLocation) return "";
  const km = haversineKm(userLocation.lat, userLocation.lng, lat, lng);
  return km < 1 ? `${Math.round(km * 1000)} m away` : `${km.toFixed(1)} km away`;
}

function mapEmbedUrl(lat, lng, zoom = 15) {
  return `https://maps.google.com/maps?q=${lat},${lng}&z=${zoom}&output=embed`;
}

function directionsUrl(lat, lng) {
  return `https://www.google.com/maps/dir/?api=1&destination=${lat},${lng}`;
}

function mapsSearchUrl(query) {
  return `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(query)}`;
}

function clearSuggestions() {
  suggestionsBox.innerHTML = "";
  suggestionsBox.hidden = true;
}

function renderSuggestions(query) {
  const q = query.trim().toLowerCase();
  if (!q) {
    clearSuggestions();
    return;
  }

  const brandMatches = BRANDS.filter((b) => b.name.toLowerCase().includes(q)).slice(0, 5);
  const mallMatches = MALLS.filter(
    (m) => m.name.toLowerCase().includes(q) || m.city.toLowerCase().includes(q)
  ).slice(0, 5);

  if (brandMatches.length === 0 && mallMatches.length === 0) {
    clearSuggestions();
    return;
  }

  suggestionsBox.innerHTML = "";
  for (const brand of brandMatches) {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.innerHTML = `<span>${brand.icon}</span><span>${brand.name}</span><span class="tag">Brand</span>`;
    btn.addEventListener("click", () => runSearch(brand.name));
    suggestionsBox.appendChild(btn);
  }
  for (const mall of mallMatches) {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.innerHTML = `<span>\u{1F3EC}</span><span>${mall.name} — ${mall.city}</span><span class="tag">Mall</span>`;
    btn.addEventListener("click", () => runSearch(mall.name));
    suggestionsBox.appendChild(btn);
  }
  suggestionsBox.hidden = false;
}

searchInput.addEventListener("input", () => renderSuggestions(searchInput.value));
searchInput.addEventListener("focus", () => renderSuggestions(searchInput.value));
document.addEventListener("click", (e) => {
  if (!searchForm.contains(e.target)) clearSuggestions();
});

searchForm.addEventListener("submit", (e) => {
  e.preventDefault();
  runSearch(searchInput.value);
});

document.querySelectorAll(".chip[data-query]").forEach((chip) => {
  chip.addEventListener("click", () => {
    searchInput.value = chip.dataset.query;
    runSearch(chip.dataset.query);
  });
});

locateBtn.addEventListener("click", () => {
  if (!navigator.geolocation) {
    locateStatus.textContent = "Geolocation isn't available in this browser.";
    return;
  }
  locateStatus.textContent = "Locating you…";
  navigator.geolocation.getCurrentPosition(
    (pos) => {
      userLocation = { lat: pos.coords.latitude, lng: pos.coords.longitude };
      locateStatus.textContent = "Location found — showing what's nearest.";
      renderNearMe();
    },
    () => {
      locateStatus.textContent = "Couldn't get your location. You can still search by brand, mall, or city.";
    }
  );
});

function runSearch(rawQuery) {
  const query = rawQuery.trim();
  clearSuggestions();
  if (!query) return;

  const q = query.toLowerCase();
  const mall = MALLS.find((m) => m.name.toLowerCase() === q) || MALLS.find((m) => m.name.toLowerCase().includes(q));
  const brand = BRANDS.find((b) => b.name.toLowerCase() === q) || BRANDS.find((b) => b.name.toLowerCase().includes(q));

  if (mall) {
    renderMallView(mall.id);
    return;
  }
  if (brand) {
    renderBrandView(brand.id);
    return;
  }

  const cityMalls = MALLS.filter((m) => m.city.toLowerCase().includes(q));
  if (cityMalls.length) {
    renderCityView(query, cityMalls);
    return;
  }

  renderEmptyState(query);
}

function show(html) {
  resultsSection.innerHTML = html;
  resultsSection.hidden = false;
  resultsSection.scrollIntoView({ behavior: "smooth", block: "start" });
}

function backButton(label, onClick) {
  const btn = document.createElement("button");
  btn.className = "back-link";
  btn.type = "button";
  btn.innerHTML = `← ${label}`;
  btn.addEventListener("click", onClick);
  return btn;
}

function renderEmptyState(query) {
  show(`
    <div class="empty-state">
      <p>No brand or mall matches "<strong>${escapeHtml(query)}</strong>" yet.</p>
      <p>Try a brand like <em>Apple</em> or <em>Zara</em>, a mall like <em>MyZeil</em>, or a city like <em>Dubai</em>.</p>
    </div>
  `);
}

function renderCityView(query, cityMalls) {
  const city = cityMalls[0].city;
  const cards = cityMalls
    .map(
      (m) => `
      <div class="card">
        <span class="icon">\u{1F3EC}</span>
        <h3>${m.name}</h3>
        <p class="muted">${m.address}</p>
        ${userLocation ? `<span class="distance">${distanceLabel(m.lat, m.lng)}</span>` : ""}
        <div class="links">
          <a href="#" data-mall="${m.id}">View directory</a>
          <a href="${directionsUrl(m.lat, m.lng)}" target="_blank" rel="noopener">Directions</a>
        </div>
      </div>
    `
    )
    .join("");

  show(`
    <div class="result-header">
      <div>
        <h2>Malls in ${escapeHtml(city)}</h2>
        <p class="meta">${cityMalls.length} shopping destination${cityMalls.length > 1 ? "s" : ""}</p>
      </div>
    </div>
    <div class="grid">${cards}</div>
  `);

  resultsSection.querySelectorAll("[data-mall]").forEach((el) => {
    el.addEventListener("click", (e) => {
      e.preventDefault();
      renderMallView(el.dataset.mall);
    });
  });
}

function renderBrandView(brandId) {
  const brand = getBrand(brandId);
  let stores = brand.stores.map((s) => ({ ...s, mall: getMall(s.mall) }));

  if (userLocation) {
    stores = stores
      .map((s) => ({ ...s, km: haversineKm(userLocation.lat, userLocation.lng, s.mall.lat, s.mall.lng) }))
      .sort((a, b) => a.km - b.km);
  }

  const cards = stores
    .map(
      (s) => `
      <div class="card">
        <span class="icon">${brand.icon}</span>
        <h3>${brand.name}</h3>
        <p class="muted">${s.mall.name}, ${s.mall.city}</p>
        <p class="muted">${s.floor} · Unit ${s.zone}</p>
        <p class="muted">Open ${s.hours}</p>
        ${userLocation ? `<span class="distance">${distanceLabel(s.mall.lat, s.mall.lng)}</span>` : ""}
        <div class="links">
          <a href="#" data-mall="${s.mall.id}">View mall directory</a>
          <a href="${directionsUrl(s.mall.lat, s.mall.lng)}" target="_blank" rel="noopener">Directions</a>
        </div>
      </div>
    `
    )
    .join("");

  const container = document.createElement("div");
  container.appendChild(backButton("Back to search", () => {
    resultsSection.hidden = true;
    searchInput.focus();
  }));
  container.insertAdjacentHTML(
    "beforeend",
    `
    <div class="result-header">
      <div>
        <h2>${brand.icon} ${brand.name}</h2>
        <p class="meta">${brand.category} · ${stores.length} location${stores.length > 1 ? "s" : ""}</p>
      </div>
    </div>
    <div class="grid">${cards}</div>
  `
  );

  resultsSection.innerHTML = "";
  resultsSection.appendChild(container);
  resultsSection.hidden = false;
  resultsSection.scrollIntoView({ behavior: "smooth", block: "start" });

  resultsSection.querySelectorAll("[data-mall]").forEach((el) => {
    el.addEventListener("click", (e) => {
      e.preventDefault();
      renderMallView(el.dataset.mall);
    });
  });
}

function renderMallView(mallId) {
  const mall = getMall(mallId);
  const stores = storesForMall(mallId);
  const activeFloor = mall.floors[0];
  renderMallViewFloor(mall, stores, activeFloor);
}

function renderMallViewFloor(mall, stores, activeFloor) {
  const floorTabs = mall.floors
    .map(
      (f) =>
        `<button type="button" data-floor="${escapeAttr(f)}" class="${f === activeFloor ? "active" : ""}">${f}</button>`
    )
    .join("");

  const floorStores = stores.filter((s) => s.floor === activeFloor);
  const tiles = floorStores.length
    ? floorStores
        .map(
          (s) => `
      <div class="store-tile" title="${s.brand.name} — ${s.hours}">
        <span class="zone">${s.zone}</span>
        <span class="icon">${s.brand.icon}</span>
        <div>${s.brand.name}</div>
      </div>
    `
        )
        .join("")
    : `<p class="empty-state">No stores mapped on this floor yet.</p>`;

  const container = document.createElement("div");
  container.appendChild(backButton("Back to search", () => {
    resultsSection.hidden = true;
    searchInput.focus();
  }));
  container.insertAdjacentHTML(
    "beforeend",
    `
    <div class="result-header">
      <div>
        <h2>\u{1F3EC} ${mall.name}</h2>
        <p class="meta">${mall.address}${userLocation ? " · " + distanceLabel(mall.lat, mall.lng) : ""}</p>
      </div>
      <a class="btn ghost" href="${directionsUrl(mall.lat, mall.lng)}" target="_blank" rel="noopener">Get directions</a>
    </div>
    <div class="map-embed">
      <iframe src="${mapEmbedUrl(mall.lat, mall.lng)}" loading="lazy" title="Map of ${escapeAttr(mall.name)}"></iframe>
    </div>
    <div class="floor-tabs">${floorTabs}</div>
    <div class="floor-plan">${tiles}</div>
  `
  );

  resultsSection.innerHTML = "";
  resultsSection.appendChild(container);
  resultsSection.hidden = false;
  resultsSection.scrollIntoView({ behavior: "smooth", block: "start" });

  resultsSection.querySelectorAll("[data-floor]").forEach((btn) => {
    btn.addEventListener("click", () => renderMallViewFloor(mall, stores, btn.dataset.floor));
  });
}

function renderNearMe() {
  if (!userLocation) return;

  const nearestMalls = MALLS.map((m) => ({ ...m, km: haversineKm(userLocation.lat, userLocation.lng, m.lat, m.lng) }))
    .sort((a, b) => a.km - b.km)
    .slice(0, 3);

  const cards = nearestMalls
    .map(
      (m) => `
      <div class="card">
        <span class="icon">\u{1F3EC}</span>
        <h3>${m.name}</h3>
        <p class="muted">${m.city}, ${m.country}</p>
        <span class="distance">${distanceLabel(m.lat, m.lng)}</span>
        <div class="links">
          <a href="#" data-mall="${m.id}">View directory</a>
          <a href="${directionsUrl(m.lat, m.lng)}" target="_blank" rel="noopener">Directions</a>
        </div>
      </div>
    `
    )
    .join("");

  show(`
    <div class="result-header">
      <div>
        <h2>Nearest to you</h2>
        <p class="meta">Based on your current location</p>
      </div>
    </div>
    <div class="grid">${cards}</div>
  `);

  resultsSection.querySelectorAll("[data-mall]").forEach((el) => {
    el.addEventListener("click", (e) => {
      e.preventDefault();
      renderMallView(el.dataset.mall);
    });
  });
}

function escapeHtml(str) {
  const div = document.createElement("div");
  div.textContent = str;
  return div.innerHTML;
}

function escapeAttr(str) {
  return escapeHtml(str).replace(/"/g, "&quot;");
}
