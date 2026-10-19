(ns geo
  "Free, keyless geo services: Photon for address search and OSRM's public demo
  server for driving distance and time. Both are best effort, so every call
  resolves to nil on failure and callers fall back to straight-line distance.")

(def photon "https://photon.komoot.io")
(def osrm "https://router.project-osrm.org")
;; minLon,minLat,maxLon,maxLat around North Carolina
(def nc-bbox "-84.33,33.84,-75.46,36.59")
(def meters-per-mile 1609.344)

(defn miles
  "Straight-line (haversine) miles between two {:lat :lng}."
  [a b]
  (let [rad #(* % (/ js/Math.PI 180))
        dlat (rad (- (:lat b) (:lat a)))
        dlng (rad (- (:lng b) (:lng a)))
        h (+ (js/Math.pow (js/Math.sin (/ dlat 2)) 2)
             (* (js/Math.cos (rad (:lat a))) (js/Math.cos (rad (:lat b)))
                (js/Math.pow (js/Math.sin (/ dlng 2)) 2)))]
    (* 2 3958.8 (js/Math.asin (js/Math.sqrt h)))))

(defn fmt-trip
  "{:mi 3.24 :min 8.2 :driving true} → `3.2 mi · 8 min drive`"
  [{:keys [mi min driving]}]
  (let [dist (str (.toFixed mi (if (< mi 10) 1 0)) " mi")]
    (if driving
      (let [m (js/Math.max 1 (js/Math.round min))]
        (str dist " · "
             (if (< m 60) (str m " min") (str (js/Math.floor (/ m 60)) " hr " (mod m 60) " min"))
             " drive"))
      (str dist " away"))))

(defn- json-or-nil [resp] (when (.-ok resp) (.json resp)))

(defn- lnglats [points] (.join (.map points #(str (:lng %) "," (:lat %))) ";"))

(defn straight-trips
  "{site-id {:mi :driving false}} for every site."
  [origin sites]
  (reduce (fn [m site] (assoc! m (:id site) {:mi (miles origin site) :driving false}))
          {} sites))

(defn drive-trips
  "Promise of {site-id {:mi :min :driving true}} from one OSRM table request,
  or nil when OSRM is unavailable."
  [origin sites]
  (-> (js/fetch (str osrm "/table/v1/driving/" (lnglats (into [origin] sites))
                     "?sources=0&annotations=duration,distance"))
      (.then json-or-nil)
      (.then (fn [r]
               (when (= "Ok" (:code r))
                 (let [durations (first (:durations r))
                       distances (first (:distances r))]
                   (reduce (fn [m [i site]]
                             (let [dur (aget durations (inc i))
                                   dist (aget distances (inc i))]
                               (assoc! m (:id site)
                                       (if (and (some? dur) (some? dist))
                                         {:mi (/ dist meters-per-mile) :min (/ dur 60) :driving true}
                                         {:mi (miles origin site) :driving false}))))
                           {} (map-indexed vector sites))))))
      (.catch (fn [_] nil))))

(defn route
  "Promise of the driving route {:mi :min :driving true :path [[lat lng]...]},
  or nil when OSRM is unavailable."
  [origin site]
  (-> (js/fetch (str osrm "/route/v1/driving/" (lnglats [origin site])
                     "?overview=full&geometries=geojson"))
      (.then json-or-nil)
      (.then (fn [r]
               (when-let [rt (first (:routes r))]
                 {:mi (/ (:distance rt) meters-per-mile)
                  :min (/ (:duration rt) 60)
                  :driving true
                  :path (.map (:coordinates (:geometry rt)) (fn [[lng lat]] [lat lng]))})))
      (.catch (fn [_] nil))))

(defn- feature->place [f]
  (let [p (:properties f)
        [lng lat] (:coordinates (:geometry f))
        street (when (:street p) (.trim (str (or (:housenumber p) "") " " (:street p))))
        town (.join (.filter [(:city p) (str "NC " (or (:postcode p) ""))] some?) ", ")
        named? (and (:name p) (not= (:name p) (:street p)))]
    {:lat lat
     :lng lng
     :title (if named? (:name p) (or street (:name p) town))
     :subtitle (if named? (.join (.filter [street town] some?) ", ") town)
     :county (some-> (:county p) (.replace #"\s+County$" ""))}))

(defn search-places
  "Promise of up to 6 NC places matching `q`, ranked near `near` {:lat :lng}."
  [q near]
  (let [params (js/URLSearchParams. {:q q :limit "8" :lang "en" :bbox nc-bbox})]
    (when near
      (.set params "lat" (:lat near))
      (.set params "lon" (:lng near)))
    (-> (js/fetch (str photon "/api/?" params))
        (.then json-or-nil)
        (.then (fn [r]
                 (let [seen #{}]
                   (->> (or (:features r) [])
                        (filter #(= "North Carolina" (:state (:properties %))))
                        (map feature->place)
                        (filter (fn [p]
                                  (let [k (str (:title p) "|" (:subtitle p))]
                                    (when-not (contains? seen k)
                                      (conj! seen k)
                                      true))))
                        (take 6)
                        vec))))
        (.catch (fn [_] [])))))

(defn reverse-place
  "Promise of the place at {:lat :lng}, for the current location's county."
  [{:keys [lat lng]}]
  (-> (js/fetch (str photon "/reverse?lat=" lat "&lon=" lng "&lang=en"))
      (.then json-or-nil)
      (.then #(some-> (first (:features %)) feature->place))
      (.catch (fn [_] nil))))
