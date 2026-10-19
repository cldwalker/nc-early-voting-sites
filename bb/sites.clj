(ns sites
  "Converts the state's tab-separated early voting list into `sites.json`.

  The NCSBE's statewide list has one row per site per day and no coordinates. This
  collapses it to one entry per site with its hours as a vector aligned to
  `:dates`, and geocodes each address. Geocodes are cached by address so only
  new or changed addresses hit the network. `--overrides` maps an address
  (`STREET, CITY, NC ZIP`) to `[lat lng]` for addresses the geocoders get wrong."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.data.csv :as csv]
            [clojure.string :as str]))

(def statewide-list-url
  "The NCSBE's download of every early voting site's daily hours."
  "https://vt.ncsbe.gov/OSSite?handler=StatewideList")
(def census-batch-url
  "https://geocoding.geo.census.gov/geocoder/locations/addressbatch")
(def nominatim-url "https://nominatim.openstreetmap.org/search")
(def user-agent "nc-early-vote-sites/1.0 (https://github.com/cldwalker/nc-early-vote-sites)")

;; ---------------------------------------------------------------------------
;; Parsing

(def columns [:election :county :boe? :name :street :city-line :date :hours])

(defn- parse-rows
  "Parses the list, failing when it isn't one, e.g. an HTML error page."
  [text]
  (let [lines (->> (str/split-lines (str/replace text #"^﻿" ""))
                   (remove str/blank?))
        split #(str/split % #"\t" -1)
        bad (first (remove #(= (count columns) (count (split %))) lines))]
    (when (or (empty? lines) bad)
      (throw (ex-info (str "Not a tab-separated statewide list. First bad line: "
                           (pr-str (subs (str bad) 0 (min 200 (count (str bad))))))
                      {:lines (count lines)})))
    (map #(zipmap columns (map str/trim (split %))) lines)))

(defn- read-input
  "Reads the list from a URL or a local file."
  [input]
  (if (re-find #"^https?://" input)
    (do (println "Downloading" input)
        (:body (http/get input {:headers {"User-Agent" user-agent} :timeout 120000})))
    (slurp input)))

(defn- iso-date
  "Converts `10/15/2026` to `2026-10-15` so dates sort and parse in JS."
  [s]
  (let [[m d y] (str/split s #"/")]
    (format "%s-%s-%s" y m d)))

(def upper-words
  #{"NC" "US" "NW" "NE" "SW" "SE" "YMCA" "YWCA" "BOE" "II" "III" "IV" "UNC" "NCSU"
    "ECU" "WSSU" "NCCU" "NCA&T" "A&T" "VFW" "AME" "CMS" "PO" "HWY" "MLK" "VFD" "FD"
    "EMS" "EV" "HQ" "CVCC" "CCCC" "CPCC" "NCRC" "DSS"})
(def lower-words #{"of" "the" "and" "at" "for" "in" "on" "a"})

(defn- title-word [i w]
  (let [lw (str/lower-case w)]
    (cond
      (contains? upper-words w) w
      (and (pos? i) (contains? lower-words lw)) lw
      ;; 1ST, 22ND, 3RD
      (re-matches #"\d+(ST|ND|RD|TH)" w) lw
      (re-matches #"MC[A-Z]{2,}" w) (str "Mc" (str/capitalize (subs w 2)))
      :else (str/replace lw #"(^|[-'/(.])([a-z])"
                         (fn [[_ sep c]] (str sep (str/upper-case c)))))))

(defn title-case
  "Title-cases the state's ALL CAPS names, e.g. `MCDOWELL COUNTY BOARD OF
  ELECTIONS` → `McDowell County Board of Elections`."
  [s]
  (->> (str/split s #"\s+")
       (map-indexed title-word)
       (str/join " ")))

(defn slug
  "Site id used in `?site=`. Street addresses are unique statewide."
  [street]
  (-> (str/lower-case street)
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-|-$" "")))

(defn- parse-city-line
  "`BURLINGTON, NC 27217` → {:city \"BURLINGTON\" :state \"NC\" :zip \"27217\"}"
  [s]
  (if-let [[_ city state zip] (re-matches #"(.+?),\s*([A-Z]{2})\s+(\d{5}).*" s)]
    {:city city :state state :zip zip}
    {:city s :state "NC" :zip ""}))

(defn collapse
  "Collapses one-row-per-day `rows` into `{:election :dates :sites}`."
  [rows]
  (let [dates (->> rows (map :date) distinct (sort-by iso-date) vec)
        by-site (group-by (juxt :county :street) rows)
        ;; Keep the file's order: county, then the order sites first appear
        site-keys (distinct (map (juxt :county :street) rows))]
    {:election (iso-date (:election (first rows)))
     :dates (mapv iso-date dates)
     :sites
     (vec
      (for [k site-keys
            :let [site-rows (by-site k)
                  {:keys [county name street city-line boe?]} (first site-rows)
                  hours-by-date (into {} (map (juxt :date :hours)) site-rows)]]
        {:id (slug street)
         :county county
         :name name
         :street street
         :city-line city-line
         :boe (= "Y" boe?)
         ;; "" means the site isn't listed that day, which the UI shows as closed
         :hours (mapv #(get hours-by-date % "") dates)}))}))

;; ---------------------------------------------------------------------------
;; Geocoding

(defn- address-key [{:keys [street city-line]}]
  (str street ", " city-line))

(defn- census-batch
  "Geocodes `sites` in one request to the Census batch geocoder.
  Returns {address-key [lat lng]} for the matches."
  [sites]
  (let [rows (for [[i s] (map-indexed vector sites)
                   :let [{:keys [city state zip]} (parse-city-line (:city-line s))]]
               [(str i) (:street s) city state zip])
        body (with-out-str (csv/write-csv *out* rows))
        resp (http/post census-batch-url
                        {:multipart [{:name "benchmark" :content "Public_AR_Current"}
                                     {:name "addressFile" :content body
                                      :file-name "addresses.csv"
                                      :content-type "text/csv"}]
                         :timeout 300000})]
    (into {}
          (for [[id _input match _exact _matched lonlat] (csv/read-csv (:body resp))
                :when (= "Match" match)
                :let [[lng lat] (map parse-double (str/split lonlat #","))]]
            [(address-key (nth sites (parse-long id))) [lat lng]]))))

(defn- in-county?
  "True when a geocoder's county name (e.g. `Wake County`, `Wake`) is `county`."
  [county result-county]
  (and result-county
       (= (str/lower-case (str/replace result-county #"(?i)\s+county$" ""))
          (str/lower-case county))))

(defn- get-json
  "GETs JSON from a free geocoder. Returns nil, with a warning, when it refuses
  (e.g. 429 rate limiting) so one bad lookup doesn't stop the run."
  [url query-params]
  (let [resp (try (http/get url {:query-params query-params
                                 :headers {"User-Agent" user-agent}
                                 :throw false})
                  (catch Exception e {:status (ex-message e)}))]
    (if (= 200 (:status resp))
      (json/parse-string (:body resp) true)
      (println "WARNING:" url "returned" (:status resp) "for" (pr-str (:q query-params))))))

(defn- census-oneline
  "Geocodes one address with the Census. Its one-line parser matches some
  addresses that its batch geocoder misses."
  [q _county]
  (when-let [m (first (get-in (get-json "https://geocoding.geo.census.gov/geocoder/locations/onelineaddress"
                                        {:q q :address q :benchmark "Public_AR_Current" :format "json"})
                              [:result :addressMatches]))]
    [(get-in m [:coordinates :y]) (get-in m [:coordinates :x])]))

(defn- nominatim
  "Searches Nominatim, which allows at most one request per second."
  [q county]
  (Thread/sleep 1100)
  (let [hit (first (get-json nominatim-url {:q q :format "json" :limit "1"
                                            :addressdetails "1" :countrycodes "us"}))]
    (when (and hit (in-county? county (get-in hit [:address :county])))
      [(parse-double (:lat hit)) (parse-double (:lon hit))])))

(defn- photon
  "Searches Photon, which is fuzzier than Nominatim. Biased toward central NC."
  [q county]
  (Thread/sleep 500)
  (let [hit (first (:features (get-json "https://photon.komoot.io/api/"
                                        {:q q :limit "1" :lat "35.5" :lon "-79.5"})))]
    (when (and hit (in-county? county (get-in hit [:properties :county])))
      (let [[lng lat] (get-in hit [:geometry :coordinates])]
        [lat lng]))))

(defn- fallback-geocode
  "Geocodes one site the Census missed. Tries the street address, then the
  site's name, and only accepts a result in the site's county."
  [{:keys [county name street city-line] :as site}]
  (let [street (str/replace street #"\s+#\s*\S+$" "")
        city-line (str/replace city-line #"\s+\d{5}.*" "")]
    (some (fn [[search q]] (search q county))
          [[census-oneline (address-key site)]
           [photon (str street ", " city-line)]
           [nominatim (str street ", " city-line)]
           [photon (str name ", " city-line)]
           [nominatim (str name ", " city-line)]])))

(defn- round6 [x] (/ (Math/round (* x 1e6)) 1e6))

(defn geocode
  "Returns the geocode cache updated with any of `sites` it lacks. Calls
  `save!` with the cache after each step so an interrupted run keeps its work."
  [sites cache save!]
  (let [missing (vec (remove #(contains? cache (address-key %)) sites))]
    (if (empty? missing)
      cache
      (let [_ (println "Geocoding" (count missing) "addresses with the Census batch geocoder...")
            census (census-batch missing)
            cache (doto (merge cache census) save!)
            still (remove #(contains? cache (address-key %)) missing)]
        (println "Census matched" (count census) "of" (count missing))
        (reduce (fn [cache site]
                  (if-let [ll (fallback-geocode site)]
                    (do (println "Fallback matched" (address-key site))
                        (doto (assoc cache (address-key site) ll) save!))
                    (do (println "WARNING: no geocode for" (address-key site))
                        cache)))
                cache
                still)))))

(defn- read-json [path]
  (if (fs/exists? path)
    (json/parse-string (slurp path))
    {}))

(defn- write-json [path data]
  (spit path (json/generate-string data)))

;; ---------------------------------------------------------------------------
;; Entry point

(defn- display-site
  "Adds coordinates and title-cases the names shown in the UI."
  [coords {:keys [county name street city-line] :as site}]
  (let [[lat lng] (coords (address-key site))
        {:keys [city state zip]} (parse-city-line city-line)]
    (-> site
        (dissoc :city-line)
        (assoc :county (title-case county)
               :name (title-case name)
               :street (title-case street)
               :city (str (title-case city) ", " state " " zip)
               :lat (some-> lat round6)
               :lng (some-> lng round6)))))

(def spec
  ;; A vector keeps --help in this order
  [[:input {:ref "<url|file>"
            :desc "The state's tab-separated early voting list"
            :default statewide-list-url
            :default-desc "NCSBE's statewide list"}]
   [:output {:ref "<file>" :desc "JSON file the page loads" :default "sites.json"}]
   [:cache {:ref "<file>" :desc "Geocode cache, keyed by address" :default "geocodes.json"}]
   [:overrides {:ref "<file>"
                :desc "Manual [lat lng] geocodes, keyed by address. Wins over the cache"
                :default "geocode-overrides.json"}]
   [:pretty {:coerce :boolean :desc "Pretty-print the output"}]])

(def sites-cli
  "bb.edn :cli config for `bb sites`"
  {:epilog ["Examples:"
            "  bb sites                                  # download the latest list"
            "  bb sites --input StatewideList.txt        # use a local copy"
            "  bb sites --output /tmp/sites.json --pretty"]})

(defn build
  "Downloads the [NCSBE's statewide list](https://vt.ncsbe.gov/OSSite?handler=StatewideList)
   into sites.json and geocodes new addresses.  It geocodes only new addresses
   (the results are cached in `geocodes.json`). For any site it can't geocode,
   add `[lat, lng]` to `geocode-overrides.json` under the address it prints,
   then commit the result."
  {:org.babashka/cli {:spec spec :restrict true}}
  [{:keys [input output cache overrides pretty]}]
  (let [data (collapse (parse-rows (read-input input)))
        manual (read-json overrides)
        cached (geocode (remove #(contains? manual (address-key %)) (:sites data))
                        (read-json cache)
                        #(write-json cache (into (sorted-map) %)))
        coords (merge cached manual)
        sites (mapv #(display-site coords %) (:sites data))
        missing (filter (comp nil? :lat) sites)
        out (assoc data :sites sites)]
    (spit output (if pretty
                   (json/generate-string out {:pretty true})
                   (json/generate-string out)))
    (println (format "Wrote %d sites in %d counties to %s"
                     (count sites) (count (distinct (map :county sites))) output))
    (when (seq missing)
      (println "Sites without coordinates (add them to" overrides "):")
      (doseq [s missing] (println " " (:street s) "-" (:city s))))))

