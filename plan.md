## Background

I'd like to build an alternative to https://vt.ncsbe.gov/EVSite/. It provides a basic search service for early voting sites in NC. The interface is initially a basic dropdown which let's the user pick their county. Once the county is selected, a map of sites shows up. 

## Features

The webpage in this repo improves on the above site and has the following features:
* The initial county input should be a dropdown but it should be a modern input in which a user types any part of a county and it matches instantly
* Most major selections should be linkable so a user can easily share a county or site with someone else.
  Once a county is selected, a query param is appended e.g. ?county=COUNTY
* Once a county is selected, a map showing all county sites shows with pins for each site
* Once a county is selected, an optional input appears with 'Find closest site to this address'. This feature should work as follows:
    * An address autocompletes if possible. Don't want to use paid service for this though
    * Once the address is input, a pin is placed on the map and it draws a line to the closest site
    * The distance and optional estimated time to that site should be displayed. It's find to use a pre-packaged directions feature if that's easier
    * A nice to have would be to let a user pick a different site and see the distance. But not if it means if moving to a paid platofmr or adding much complexity
* Once a county is selected, a list of sites shows up in the left sidebar, showing the site's name, the abbreviated adddress and open hours for today. An abbreviated address only shows the street address and removes the city, state and zip.
* If a user can click on a site in the sidebar, the left sidebar changes to a site's full details - full address and all open hours. The address should be a _target link to google maps . The sidebar should have a link to navigate back to the county site list. A query param is appended e.g. ?site=some-unique-id . The unique-id could be the abbreviated street address or whatever's most consistent for a link
* If a user clicks on a county map of sites, it should have the same behavior as clicking on the sidebar - add query param and show site info in left sidebar
* At any time a user can change counties and the previous county and site query params should clear

## Implementation and Constraints

* The webpage should work on latest chrome and firefox, for desktop and mobile
* The webpage should provide a modern, easy to use UX.
* Please use nice animations where possible as long as they don't increase .js or .css too much and doesn't make UX worse
* The webpage should only consist of html, css, js and optional images. All js should be built with this compile-to-js language - https://github.com/squint-cljs/squint . Use npm for any js deps.
* Use clojure* skills to help with writing squint. See ~/code/repo/durham-early-vote-race for a good example of using squint
* All the data for county sites is available via https://vt.ncsbe.gov/OSSite?handler=StatewideList.
* Supports Spanish translation. An EN/ES switch sits at the top right of the page. Nothing from Google loads until ES is picked, and then Google's website translator translates the page. While it's in Spanish the URL has `?lang=es`, which is kept across in-app navigation and removed when switching back to EN. Google's `googtrans` cookie brings Spanish back on a return visit, and `?lang=en` forces English.
  * Site names, street addresses, address search results and map tooltips are `notranslate`, so addresses stay usable and names match signage. Standalone county names (the county picker) are `notranslate` too, since Google turns "Wake" into "Despertar". Inside sentences, "X County" is left translatable because Google renders it naturally as "condado de X", except Scotland and Transylvania, which it renders as Escocia and Transilvania.
  * Text Google can't handle well is localized directly: the county picker's "N sites" counts (too short-lived for Google), the "Today" pill (CSS keyed off the `lang` Google sets on `<html>`) and the map credits ("Leaflet" would become "Folleto").
* Counties with a live early voting wait-time page e.g. Buncombe, Durham, Orange, Wake show a "Site Waiting Times" link.

## Decisions

* Data loading: `StatewideList.txt` is preprocessed into a single `sites.json` (~21KB gzipped for all 367 sites in 100 counties). The page fetches it in parallel with the JS. Per-county splitting was rejected because the whole state is smaller than one map tile, so splitting only adds latency.
* `StatewideList.txt` has no coordinates, so preprocessing geocodes each site with the free US Census batch geocoder. Results are cached in `geocodes.json` so re-runs don't hit the network. Misses fall back to the Census one-line geocoder, then Photon and Nominatim. A fallback match is accepted only if it's in the site's county. `geocode-overrides.json` holds manual fixes. Two sites came from manual lookups: Rockfish Community Center (Nominatim by name) and Lois G Britt in Kenansville, which is approximate (town center) until someone finds its exact location.
* The site id in `?site=` is a slug of the street address, e.g. `810-wicker-st`. Street addresses are unique statewide.
* No paid services:
  * Map: Leaflet with the standard OpenStreetMap tiles. They need no key, and CSS darkens them in dark mode. CARTO tiles were dropped because they now require an API key.
  * Address autocomplete: Photon (komoot), free and keyless. Nominatim's policy forbids autocomplete.
  * Closest site: chosen by straight-line distance. Driving distance and time come from the public OSRM demo server, falling back to straight-line miles if it fails.
  * Users can pick another site and see its distance from their address.
* Preprocessing title-cases the state's ALL CAPS names and keeps known acronyms (MLK, VFD, EV, …) upper-case.
* Closest site is chosen by driving time (one OSRM table request for all county sites). Once there's an address, the site list is sorted nearest first and shows each site's distance and drive time, which covers the "pick a different site" nice-to-have. The route to the closest or selected site is drawn along the roads.
* When the address is in a different county, a warning explains that early voting must happen in your own county and offers to switch counties.
* "Use my location" (browser geolocation plus a Photon reverse lookup) is an alternative to typing an address.
* Before any county is picked, the map shows every site in the state as a dot. Clicking a dot opens that site and its county.
* The site list shows today's status in Eastern time: open now, opens later, closed today (with the next open day), or before/after the early voting period.
* The site detail view has Directions (Google Maps) and Share (Web Share API, or copy the link) buttons.
* Layout: on desktop the panel is on the left and the map on the right. On phones they stack (header, map, then panel). Dark mode follows the OS setting.
* Browser back/forward works through pushState. Unknown `?county=` or `?site=` values fall back gracefully.
* Debug params: `?today=2026-10-20&time=13:30` pretend it's that date and time.
* `bb serve` runs a local server, and `npm run build` compiles squint plus esbuild into `dist/`. Deploys copy `index.html`, `sites.json` and `dist/` to GitHub Pages, with cache-busting query strings.
* Leaflet is served from this repo as its own file (`dist/leaflet.js`, copied from npm), not bundled or loaded from a CDN. It loads `async`, so the panel (county search, list, details, address search) works before the map arrives, and the map catches up with whatever was picked once it does. If it fails to load, the map area says so and the panel still works. Deploys version it by Leaflet's version, not the commit, so browsers keep it cached across deploys. Sizes, gzipped: `bundle.js` ~13KB, `leaflet.js` ~43KB, `style.css` ~6KB, `sites.json` ~21KB.
