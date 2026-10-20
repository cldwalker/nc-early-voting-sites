(ns data
  "Site data helpers: ids, dates and today's hours. Hours come from the state as
  strings like `8:00AM - 7:30PM`, aligned with the `dates` in `sites.json`.")

;; Every site is in NC, so "today" and "now" are always Eastern time
(def tz "America/New_York")
;; Debug/screenshot aid: ?today=2026-10-20&time=13:30 pretends it's then
(def debug-params (js/URLSearchParams. js/location.search))

(defn slug
  "`New Hanover` → `new-hanover`. Used for `?county=` and matches `bb/sites.clj`."
  [s]
  (-> (.toLowerCase (str s))
      (.replace (js/RegExp. "[^a-z0-9]+" "g") "-")
      (.replace (js/RegExp. "^-+|-+$" "g") "")))

(defn today-iso []
  (or (.get debug-params "today")
      (.format (js/Intl.DateTimeFormat. "en-CA" {:timeZone tz}) (js/Date.))))

(defn now-minutes []
  (if-let [t (.get debug-params "time")]
    (let [[h m] (.split t ":")]
      (+ (* 60 (js/Number h)) (js/Number m)))
    (let [parts (.formatToParts (js/Intl.DateTimeFormat. "en-US" {:timeZone tz :hour "numeric"
                                                                  :minute "numeric" :hourCycle "h23"})
                                (js/Date.))
          part (fn [type] (js/Number (.-value (.find parts #(= type (.-type %))))))]
      (+ (* 60 (part "hour")) (part "minute")))))

;; Formats an ISO date without shifting it a day in western timezones
(def date-format (js/Intl.DateTimeFormat. "en-US" {:weekday "short" :month "short" :day "numeric"
                                                   :timeZone "UTC"}))
(defn fmt-date [iso] (.format date-format (js/Date. (str iso "T12:00:00Z"))))

(defn- ->minutes
  "`7:30PM` → 1170"
  [s]
  (when-let [[_ h m ap] (.match s #"(\d{1,2}):(\d{2})\s*([AP]M)")]
    (+ (* 60 (+ (mod (js/Number h) 12) (if (= ap "PM") 12 0)))
       (js/Number m))))

(defn parse-hours
  "`8:00AM - 7:30PM` → [480 1170]. nil when closed or unlisted."
  [s]
  (let [[a b] (.split (or s "") "-")]
    (when (and a b)
      (let [open (->minutes (.trim a))
            close (->minutes (.trim b))]
        (when (and open close) [open close])))))

(defn fmt-time
  "1170 → `7:30 PM`, 480 → `8 AM`"
  [mins]
  (let [h (js/Math.floor (/ mins 60))
        m (mod mins 60)
        h12 (let [x (mod h 12)] (if (zero? x) 12 x))]
    (str h12 (if (zero? m) "" (str ":" (.padStart (str m) 2 "0")))
         (if (>= h 12) " PM" " AM"))))

(defn fmt-hours [s]
  (if-let [[open close] (parse-hours s)]
    (str (fmt-time open) " – " (fmt-time close))
    "Closed"))

(defn- next-open
  "Index of the first day after `i` the site is open, or -1."
  [hours i]
  (let [j (.findIndex hours (fn [h k] (and (> k i) (parse-hours h))))]
    j))

(defn today-info
  "What a site's hours mean right now, as {:state :text}. `:state` is a CSS
  class: open, later, done, closed, before or after."
  [dates site]
  (let [today (today-iso)
        hours (:hours site)
        i (.indexOf dates today)]
    (cond
      (< today (first dates))
      (let [j (next-open hours -1)]
        {:state "before"
         :text (if (neg? j)
                 "No early voting hours listed"
                 (str "Opens " (fmt-date (aget dates j)) " · " (fmt-hours (aget hours j))))})

      (> today (last dates))
      {:state "after" :text (str "Early voting ended " (fmt-date (last dates)))}

      :else
      (let [h (parse-hours (aget hours i))
            now (now-minutes)
            j (next-open hours i)
            reopen (if (neg? j) "no more early voting days here" (str "reopens " (fmt-date (aget dates j))))]
        (cond
          (nil? h) {:state "closed" :text (str "Closed today · " reopen)}
          (< now (first h)) {:state "later" :text (str "Today " (fmt-hours (aget hours i)))}
          (< now (second h)) {:state "open" :text (str "Open now · until " (fmt-time (second h)))}
          :else {:state "done" :text (str "Closed for today · " reopen)})))))

(defn full-address [site] (str (:street site) ", " (:city site)))

(defn maps-url
  "Google Maps search for the site, so it shows the place and not just a pin."
  [site]
  (str "https://www.google.com/maps/search/?api=1&query="
       (js/encodeURIComponent (str (:name site) ", " (full-address site)))))

(defn directions-url [site origin]
  (str "https://www.google.com/maps/dir/?api=1"
       (if origin (str "&origin=" (:lat origin) "," (:lng origin)) "")
       "&destination=" (js/encodeURIComponent (full-address site))))

(def wait-times
  "Counties with a page of live early voting wait times, by county name."
  {"Buncombe" "https://bunco.maps.arcgis.com/apps/webappviewer/index.html?id=ae284ef93d1a40a592477a29c8929c81"
   "Durham" "https://durhamnc.maps.arcgis.com/apps/instant/nearby/index.html?appid=1d42511f6e2e45dfbd5b21d233899a64"
   "Orange" "https://www.orangecountync.gov/3013/Early-Voting-Wait-Time-Tracker"
   "Wake" "https://airtable.com/appAL6KsUEvshj4br/shrQkIIFla4bjdGzh/tbl0siEKfqhV1y6F8"})
