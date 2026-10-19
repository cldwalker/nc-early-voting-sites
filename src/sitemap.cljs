(ns sitemap
  "The Leaflet map: faint dots for every site in the state, pins for the
  chosen county's sites, and the user's address with a route to a site.

  Leaflet loads separately (`dist/leaflet.js`, async) so the panel works before
  the map arrives. Everything here is a no-op until `init!` has run.")

(def nc-bounds [[33.84 -84.32] [36.59 -75.46]])
(def reduced-motion? (.-matches (js/matchMedia "(prefers-reduced-motion: reduce)")))

(def m nil)
(def state-layer nil)
(def county-layer nil)
(def route-layer nil)
;; site id → county pin marker
(def pins (js/Map.))
(def selected-id nil)
;; True during a pan or flight, when the map's bounds aren't where it's headed
(def moving? false)

(defn when-leaflet
  "Calls `f` once the async leaflet.js has run. If it fails to load, an
  onerror in index.html shows a message in the map's place."
  [f]
  (if js/window.L
    (f)
    (.addEventListener (js/document.getElementById "leaflet-js") "load" f {:once true})))

(defn- pin-icon [i boe?]
  (js/L.divIcon {:className "pin-wrap"
                 :html (str #html [:span {:class (if boe? "pin boe" "pin") :style (str "--i:" i)}])
                 :iconSize [28 36]
                 :iconAnchor [14 34]
                 :tooltipAnchor [0 -30]}))

(defn- home-icon []
  (js/L.divIcon {:className "home-wrap"
                 :html (str #html [:span.home [:span.home-pulse]])
                 :iconSize [22 22]
                 :iconAnchor [11 11]
                 :tooltipAnchor [0 -12]}))

(defn- pad []
  ;; The panel covers no part of the map, so even padding keeps pins off the edges
  (if (< js/innerWidth 800) [24 24] [56 56]))

(defn- animate [] {:animate (not reduced-motion?) :duration 0.6 :padding (pad)})

(defn init!
  "Creates the map in #map. `on-site` is called with a site when its pin or dot
  is clicked."
  [sites on-site]
  (set! m (js/L.map "map" {:zoomSnap 0.25 :zoomControl false :attributionControl true}))
  (.addTo (js/L.control.zoom {:position "topright"}) m)
  ;; OSM's own tiles need no key. CSS darkens them in dark mode
  (.addTo (js/L.tileLayer "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
                          {:maxZoom 19
                           :attribution "&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors"})
          m)
  (.fitBounds m nc-bounds)
  (.on m "movestart" #(set! moving? true))
  (.on m "moveend" #(set! moving? false))
  (.add (.-classList js/document.documentElement) "map-ready")
  (set! state-layer (.addTo (js/L.featureGroup) m))
  (set! county-layer (.addTo (js/L.featureGroup) m))
  (set! route-layer (.addTo (js/L.featureGroup) m))
  (.forEach sites
            (fn [site]
              (-> (js/L.circleMarker [(:lat site) (:lng site)]
                                     {:radius 4 :weight 1 :className "dot"})
                  (.bindTooltip (str (:name site) " · " (:county site) " County"))
                  (.on "click" #(on-site site))
                  (.addTo state-layer))))
  m)

(defn show-county!
  "Drops pins for `sites`, or clears them and shows the state when nil."
  [sites on-site]
  (when m
    (.clearLayers county-layer)
    (.clearLayers route-layer)
    (.clear pins)
    (set! selected-id nil)
    (.toggle (.-classList (.getContainer m)) "has-county" (some? sites))
    (if (seq sites)
      (do
        (.forEach sites
                  (fn [site i]
                    (let [pin (-> (js/L.marker [(:lat site) (:lng site)]
                                               {:icon (pin-icon (min i 20) (:boe site))
                                                :title (:name site) :alt (:name site) :riseOnHover true})
                                  (.bindTooltip (:name site) {:direction "top"})
                                  (.on "click" #(on-site site))
                                  (.addTo county-layer))]
                      (.set pins (:id site) pin))))
        (.flyToBounds m (.getBounds county-layer) (animate)))
      (.flyToBounds m nc-bounds (animate)))))

(defn- pin-el [id] (some-> (.get pins id) .getElement))

(defn highlight!
  "Lifts a pin while its site is hovered in the list."
  [id on?]
  (when-let [el (pin-el id)]
    (.toggle (.-classList el) "is-hover" on?)))

(defn select!
  "Marks `site`'s pin as selected and brings it into view, or deselects when nil."
  [site]
  (when m
    (when-let [el (pin-el selected-id)] (.remove (.-classList el) "is-selected"))
    (set! selected-id (:id site))
    (when site
      (when-let [pin (.get pins (:id site))]
        (.setZIndexOffset pin 1000)
        (.add (.-classList (.getElement pin)) "is-selected"))
      (let [reveal! #(when (and (= selected-id (:id site))
                                (not (.contains (.pad (.getBounds m) -0.15) [(:lat site) (:lng site)])))
                       (.flyTo m [(:lat site) (:lng site)] (max (.getZoom m) 12) (animate)))]
        ;; Mid-flight bounds are stale, e.g. when a link opens a county and site
        (if moving? (.once m "moveend" reveal!) (reveal!))))))

(defn fit-county! []
  (when (pos? (.-size pins))
    (.flyToBounds m (.getBounds county-layer) (animate))))

(defn clear-route! [] (some-> route-layer .clearLayers))

(def route-seq 0)

(defn- draw-line!
  "Adds the route line, animating a road path drawing itself in. The dash
  trick needs the path's on-screen length, so it runs once the map stops."
  [line path]
  (let [pl (js/L.polyline line {:className (if path "route" "route straight")
                                :weight 5 :opacity 0.9 :interactive false})]
    (.addTo pl route-layer)
    (when (and path (not reduced-motion?))
      (let [el (.getElement pl)
            len (js/Math.ceil (.getTotalLength el))]
        (.setProperty (.-style el) "--len" len)
        (.add (.-classList el) "drawing")
        (.addEventListener el "animationend" #(.remove (.-classList el) "drawing") {:once true})))))

(defn show-route!
  "Shows `origin` and a line to `site`: the road `path` when known, else a
  dashed straight line. Zooms to fit both."
  [origin site path]
  (when m
    (set! route-seq (inc route-seq))
    (.clearLayers route-layer)
    (let [n route-seq
          to [(:lat site) (:lng site)]
          from [(:lat origin) (:lng origin)]
          line (or path [from to])
          bounds (js/L.latLngBounds (into [from to] line))]
      (-> (js/L.marker from {:icon (home-icon) :keyboard false :zIndexOffset 2000})
          (.bindTooltip (or (:title origin) "Your address") {:direction "top"})
          (.addTo route-layer))
      ;; Draw once the map settles. The timer covers a move that never ends
      ;; because another one interrupted it
      (let [drawn {:done false}
            draw! #(when (and (= n route-seq) (not (:done drawn)))
                     (assoc! drawn :done true)
                     (draw-line! line path))]
        (.once m "moveend" draw!)
        (js/setTimeout draw! 1500)
        (.flyToBounds m bounds (animate))))))
