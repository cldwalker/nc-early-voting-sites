(ns main
  (:require [combobox :as cb]
            [data :as d]
            [geo]
            [sitemap :as sm]
            [translate]))

(defn- $ [id] (js/document.getElementById id))

(def state
  {:dates []
   ;; ISO date of Election Day
   :election nil
   :sites []
   ;; county name → its sites, sorted by name
   :by-county {}
   :counties []
   :county nil
   :site nil
   ;; {:lat :lng :title :subtitle :county} the user searched for
   :origin nil
   ;; site id → {:mi :min :driving} from :origin
   :trips nil
   ;; Bumped per request so slow responses for an old address are dropped
   :seq 0})

(def reduced-motion? sm/reduced-motion?)
(def mobile (js/matchMedia "(max-width: 799px)"))

;; ---------------------------------------------------------------------------
;; URLs

(defn- href
  "URL for a county and optional site id. Keeps unrelated params like ?today."
  [county site-id]
  (let [p (js/URLSearchParams. js/location.search)]
    (.delete p "county")
    (.delete p "site")
    (when county (.set p "county" (d/slug county)))
    (when site-id (.set p "site" site-id))
    (let [q (.toString p)]
      (str js/location.pathname (if (seq q) (str "?" q) "")))))

(declare apply-url!)

(defn- navigate! [county site-id]
  (let [url (href county site-id)]
    (when-not (= url (str js/location.pathname js/location.search))
      (js/history.pushState nil "" url))
    (apply-url!)))

(defn- county-sites [] (get (:by-county state) (:county state)))

;; ---------------------------------------------------------------------------
;; Rendering

(defn- today-line [site]
  (let [info (d/today-info (:dates state) site)]
    #html [:span {:class (str "today " (:state info))} [:i.dot {:aria-hidden "true"}] (:text info)]))

(def untranslatable-counties
  "Counties whose names Google Translate turns into Spanish words even within
  \"X County\" (Escocia, Transilvania). Others translate naturally as
  \"condado de X\", so they're left translatable to keep that word order."
  #{"Scotland" "Transylvania"})

(defn- county-name
  "A county's name inside a sentence that mentions its county."
  [county]
  (if (contains? untranslatable-counties county)
    #html [:span.notranslate county]
    #html [:<> county]))

(defn- boe-tag [site]
  (if (:boe site) #html [:span.tag "Elections office"] ""))

(defn- trip-of [site] (get (:trips state) (:id site)))

(defn- sorted-sites
  "County sites by name, or nearest first once there's an address."
  []
  (let [sites (county-sites)
        trips (:trips state)]
    (if trips
      (.sort (.slice sites)
             (fn [a b]
               (let [ta (get trips (:id a))
                     tb (get trips (:id b))]
                 (if (and (:driving ta) (:driving tb))
                   (- (:min ta) (:min tb))
                   (- (:mi ta) (:mi tb))))))
      sites)))

(defn- closest [] (first (sorted-sites)))

(defn- render-list! []
  (let [sites (sorted-sites)
        county (:county state)
        wait-link ($ "wait-link")
        wait-url (get d/wait-times county)]
    (set! (.-hidden wait-link) (nil? wait-url))
    (if wait-url
      (set! (.-href wait-link) wait-url)
      (.removeAttribute wait-link "href"))
    (set! (.-innerHTML ($ "list-title"))
          (str #html [:<> (count sites) (if (= 1 (count sites)) " site" " sites") " in "
                      (county-name county) " County"
                      (if (:trips state) " · nearest first" "")]))
    (set! (.-innerHTML ($ "site-list"))
          (.join (.map sites
                       (fn [site i]
                         (let [trip (trip-of site)]
                           (str #html [:li.site-item {:style (str "--i:" (min i 15))}
                                       [:a.site-link {:href (href county (:id site)) :data-nav "" :data-id (:id site)}
                                        [:span.site-top
                                         [:span.site-name.notranslate (:name site)]
                                         (boe-tag site)]
                                        [:span.site-street.notranslate (:street site)]
                                        (today-line site)
                                        (if trip
                                          #html [:span.trip (geo/fmt-trip trip)]
                                          "")
                                        [:span.chev {:aria-hidden "true"} "›"]]]))))
                 ""))))

(defn- render-closest!
  "The card under the address input naming the closest site."
  []
  (let [el ($ "closest")
        origin (:origin state)
        site (when origin (closest))]
    (if-not site
      (do (set! (.-hidden el) true)
          (set! (.-innerHTML el) ""))
      (let [trip (trip-of site)
            elsewhere (when (and (:county origin) (not= (:county origin) (:county state))
                                 (get (:by-county state) (:county origin)))
                        (:county origin))]
        (set! (.-hidden el) false)
        (set! (.-innerHTML el)
              (str #html [:<>
                          (if elsewhere
                            #html [:div.warn
                                   [:p "This address looks like it's in " [:b (county-name elsewhere) " County"]
                                    ". You can only vote early at a site in the county where you're registered."]
                                   [:button.link-btn {:type "button" :id "switch-county"}
                                    "Switch to the sites in " (county-name elsewhere) " County"]]
                            "")
                          [:span.closest-label "Closest site"]
                          [:a.closest-site {:href (href (:county state) (:id site)) :data-nav ""}
                           [:span.closest-name.notranslate (:name site)]
                           [:span.closest-street.notranslate (:street site)]]
                          [:span.closest-trip (geo/fmt-trip trip)
                           (if (:driving trip)
                             ""
                             #html [:small " (straight line; driving times are unavailable right now)"])]]))))))

(defn- hours-section
  "Hours for today and the days left. Past days aren't useful to a voter."
  [site]
  (let [today (d/today-iso)
        dates (:dates state)
        days (.filter (.map dates (fn [date i] {:date date :hours (aget (:hours site) i)}))
                      #(>= (:date %) today))]
    (if (empty? days)
      #html [:p.hours-over "Early voting ended " (d/fmt-date (last dates))
             ". On Election Day, " (d/fmt-date (:election state))
             ", vote at your assigned precinct."]
      #html [:<>
             [:h3.hours-title (if (> today (first dates)) "Remaining early voting hours" "Early voting hours")]
             [:table.hours
              [:caption.sr-only "Early voting hours"]
              [:tbody
               (.map days
                     (fn [{:keys [date hours]}]
                       #html [:tr {:class (str (if (= date today) "is-today" "")
                                               (if (d/parse-hours hours) "" " is-closed"))}
                              [:th {:scope "row"} (d/fmt-date date)
                               ;; CSS supplies the text: Google would join it to the
                               ;; date as "20 de octubre. Hoy"
                               (if (= date today) #html [:span.today-tag.notranslate {:aria-label "Today"}] "")]
                              [:td (d/fmt-hours hours)]]))]]])))

(defn- render-detail! [site]
  (let [origin (:origin state)
        trip (trip-of site)
        county (:county state)]
    (set! (.-innerHTML ($ "detail-view"))
          (str #html [:<>
                      [:a.back {:href (href county nil) :data-nav ""}
                       [:span {:aria-hidden "true"} "‹ "] "All " (county-name county) " County sites"]
                      [:h2.detail-name.notranslate {:id "detail-name" :tabindex "-1"} (:name site)]
                      (boe-tag site)
                      [:a.address {:href (d/maps-url site) :target "_blank" :rel "noopener"}
                       [:span.addr-icon {:aria-hidden "true"} "📍"]
                       [:span.notranslate [:span.addr-line (:street site)] [:span.addr-line (:city site)]]
                       [:span.ext {:aria-hidden "true"} "↗"]]
                      [:p.detail-today (today-line site)]
                      (if (and origin trip)
                        #html [:p.detail-trip [:span {:aria-hidden "true"} "🚗 "]
                               (geo/fmt-trip trip) " from " [:b.notranslate (:title origin)]]
                        "")
                      [:div.actions
                       [:a.btn.primary {:href (d/directions-url site origin) :target "_blank" :rel "noopener"}
                        "Directions"]
                       [:button.btn {:type "button" :id "share"} "Share"]]
                      (hours-section site)]))))

;; ---------------------------------------------------------------------------
;; Views

(defn- show-view!
  "Shows one of the panel's views, sliding in from `dir` (:left or :right)."
  [id dir]
  (.forEach (.querySelectorAll js/document ".view")
            (fn [v]
              (let [on? (= id (.-id v))]
                (set! (.-hidden v) (not on?))
                (.remove (.-classList v) "from-left" "from-right" "fade-in")
                (when (and on? dir (not reduced-motion?))
                  (.-offsetWidth v)
                  (.add (.-classList v) (case dir :left "from-left" :right "from-right" "fade-in")))))))

(defn- scroll-panel-top! []
  (if (.-matches mobile)
    ;; On phones the panel is below the map, so bring its top into view
    (let [top (.-top (.getBoundingClientRect ($ "body")))]
      (when (or (neg? top) (> top (* 0.8 js/innerHeight)))
        (.scrollIntoView ($ "body") {:behavior (if reduced-motion? "auto" "smooth")})))
    (set! (.-scrollTop ($ "body")) 0)))

(def route-seq 0)

(defn- show-route-to!
  "Draws a route from the address to `site`, straight at first and then along
  the roads once OSRM answers."
  [site]
  (set! route-seq (inc route-seq))
  (let [origin (:origin state)
        n route-seq]
    (sm/show-route! origin site nil)
    (.then (geo/route origin site)
           (fn [r]
             (when (and r (= n route-seq))
               (sm/show-route! origin site (:path r)))))))

(defn- route-target [] (or (:site state) (closest)))

(defn- render-origin!
  "Re-renders everything that depends on the address and its distances."
  []
  (render-list!)
  (render-closest!)
  (when-let [site (:site state)] (render-detail! site)))

(defn- set-origin! [origin]
  (let [sites (county-sites)
        n (inc (:seq state))]
    (assoc! state :origin origin :seq n :trips (geo/straight-trips origin sites))
    (render-origin!)
    (show-route-to! (route-target))
    (.then (geo/drive-trips origin sites)
           (fn [trips]
             (when (and trips (= n (:seq state)))
               (let [target (route-target)]
                 (assoc! state :trips trips)
                 (render-origin!)
                 ;; Driving time can pick a different closest site than distance
                 (when-not (= target (route-target))
                   (show-route-to! (route-target)))))))))

(defn- clear-origin! []
  (assoc! state :origin nil :trips nil :seq (inc (:seq state)))
  (set! (.-value ($ "address-input")) "")
  (set! (.-hidden ($ "address-clear")) true)
  (sm/clear-route!))

(defn- set-county! [county]
  (assoc! state :county county :site nil)
  (clear-origin!)
  (render-closest!)
  (set! (.-value ($ "county-input")) (or county ""))
  (set! (.-hidden ($ "county-clear")) (nil? county))
  (.toggle (.-classList js/document.body) "has-county" (some? county))
  (sm/show-county! (when county (county-sites)) #(navigate! (:county %) (:id %)))
  (if county
    (do (render-list!)
        (show-view! "list-view" :fade))
    (show-view! "intro-view" :fade)))

(defn- set-site! [site]
  (let [prev (:site state)]
    (assoc! state :site site)
    (sm/select! site)
    (cond
      site
      (do (render-detail! site)
          (show-view! "detail-view" (when-not (= prev site) :right))
          (scroll-panel-top!)
          (.focus ($ "detail-name") {:preventScroll true})
          (when (:origin state) (show-route-to! site)))

      (:county state)
      (when prev
        (show-view! "list-view" :left)
        (if (:origin state)
          (show-route-to! (closest))
          (sm/fit-county!))))
    (set! js/document.title
          (cond site (str (:name site) " · " (:county state) " County Early Voting")
                (:county state) (str (:county state) " County Early Voting Sites")
                :else "NC Early Voting Sites"))))

(defn- find-county [param]
  (when param
    (let [s (d/slug param)]
      (.find (:counties state) #(= s (d/slug %))))))

(defn apply-url!
  "Shows the county and site in the URL."
  []
  (let [p (js/URLSearchParams. js/location.search)
        county (find-county (.get p "county"))
        site-id (.get p "site")
        site (when (and county site-id)
               (.find (get (:by-county state) county) #(= site-id (:id %))))]
    (when-not (= county (:county state))
      (set-county! county))
    (set-site! site)))

;; ---------------------------------------------------------------------------
;; Inputs

(defn- init-county-input! []
  (let [input ($ "county-input")]
    (cb/combobox
     {:input input
      :list ($ "county-list")
      :min-chars 0
      :empty #html [:<> "No NC county matches"]
      :source (fn [q]
                (let [q (.toLowerCase (.trim q))
                      matches (.filter (:counties state) #(.includes (.toLowerCase %) q))]
                  ;; Counties starting with what's typed come first
                  (.concat (.filter matches #(.startsWith (.toLowerCase %) q))
                           (.filter matches #(not (.startsWith (.toLowerCase %) q))))))
      :render (fn [county q]
                (let [n (count (get (:by-county state) county))]
                  #html [:<> [:span.opt-main.notranslate (cb/highlight county q)]
                         ;; Google doesn't get to these short-lived options in
                         ;; time, so they're translated here
                         [:span.opt-sub.notranslate n
                          (if (translate/spanish?)
                            (if (= 1 n) " sitio" " sitios")
                            (if (= 1 n) " site" " sites"))]]))
      :on-select (fn [county]
                   (.blur input)
                   (navigate! county nil))})
    (.addEventListener input "blur"
                       ;; Put back the current county if the user typed and left
                       #(js/setTimeout (fn [] (set! (.-value input) (or (:county state) ""))) 160))
    (.addEventListener ($ "county-clear") "click"
                       (fn []
                         (navigate! nil nil)
                         (.focus input)))))

(defn- place-label [place]
  (str (:title place) (if (:subtitle place) (str ", " (:subtitle place)) "")))

(defn- choose-place! [place]
  (set! (.-value ($ "address-input")) (place-label place))
  (set! (.-hidden ($ "address-clear")) false)
  (set-origin! place))

(defn- county-center []
  (when-let [sites (county-sites)]
    {:lat (/ (reduce + 0 (map :lat sites)) (count sites))
     :lng (/ (reduce + 0 (map :lng sites)) (count sites))}))

(defn- init-address-input! []
  (let [input ($ "address-input")
        status ($ "address-status")]
    (cb/combobox
     {:input input
      :list ($ "address-list")
      :min-chars 3
      :delay 250
      :empty #html [:<> "No matching NC address. Try adding a street number or city."]
      :source #(geo/search-places % (county-center))
      :render (fn [place q]
                #html [:<> [:span.opt-main.notranslate (cb/highlight (:title place) q)]
                       [:span.opt-sub.notranslate (or (:subtitle place) "")]])
      :on-select choose-place!})
    (.addEventListener input "input" #(set! (.-hidden ($ "address-clear")) (empty? (.-value input))))
    (.addEventListener ($ "address-clear") "click"
                       (fn []
                         (clear-origin!)
                         (render-origin!)
                         (if (:site state) (sm/select! (:site state)) (sm/fit-county!))
                         (.focus input)))
    (.addEventListener ($ "locate") "click"
                       (fn []
                         (if-not js/navigator.geolocation
                           (set! (.-textContent status) "Your browser can't share its location.")
                           (do
                             (set! (.-textContent status) "Finding your location…")
                             (js/navigator.geolocation.getCurrentPosition
                              (fn [pos]
                                (let [here {:lat (.-latitude (.-coords pos)) :lng (.-longitude (.-coords pos))}]
                                  (set! (.-textContent status) "")
                                  (.then (geo/reverse-place here)
                                         (fn [place]
                                           (choose-place! (assoc (or place {}) :lat (:lat here) :lng (:lng here)
                                                                 :title "Your location"
                                                                 :subtitle (if place (place-label place) "")))))))
                              (fn [_] (set! (.-textContent status) "Couldn't get your location. Type an address instead."))
                              {:timeout 10000 :maximumAge 60000})))))))

(defn- share! []
  (let [url js/location.href
        btn ($ "share")]
    (if js/navigator.share
      (-> (js/navigator.share {:title js/document.title :url url}) (.catch (fn [_])))
      (-> (js/navigator.clipboard.writeText url)
          (.then (fn []
                   (set! (.-textContent btn) "Link copied ✓")
                   (js/setTimeout #(set! (.-textContent btn) "Share") 2000)))
          (.catch (fn [_] (js/prompt "Copy this link:" url)))))))

(defn- init-events! []
  ;; In-app links are real hrefs so they can be opened in a new tab or copied
  (.addEventListener js/document "click"
                     (fn [e]
                       (let [t (.-target e)]
                         (when-let [a (.closest t "a[data-nav]")]
                           (when-not (or (.-metaKey e) (.-ctrlKey e) (.-shiftKey e) (pos? (.-button e)))
                             (.preventDefault e)
                             (js/history.pushState nil "" (.getAttribute a "href"))
                             (apply-url!)))
                         (when (.closest t "#share") (share!))
                         (when (.closest t "#switch-county")
                           (let [origin (:origin state)
                                 label (.-value ($ "address-input"))]
                             (navigate! (:county origin) nil)
                             (set! (.-value ($ "address-input")) label)
                             (set! (.-hidden ($ "address-clear")) false)
                             (set-origin! origin))))))
  (.addEventListener js/window "popstate" apply-url!)
  (let [list ($ "site-list")
        hover (fn [on?]
                (fn [e]
                  (when-let [a (.closest (.-target e) "[data-id]")]
                    (sm/highlight! (.-id (.-dataset a)) on?))))]
    (.addEventListener list "mouseover" (hover true))
    (.addEventListener list "mouseout" (hover false))))

;; ---------------------------------------------------------------------------
;; Start

(defn- init-map!
  "Creates the map once leaflet.js arrives and catches it up with whatever the
  user picked while it loaded."
  []
  (let [on-site #(navigate! (:county %) (:id %))]
    (sm/init! (:sites state) on-site)
    (when (:county state)
      (sm/show-county! (county-sites) on-site)
      (sm/select! (:site state))
      (when (:origin state)
        (show-route-to! (route-target))))))

(defn- load! [data]
  (let [sites (.filter (:sites data) #(some? (:lat %)))
        by-county (reduce (fn [m site]
                            (let [c (:county site)]
                              (when-not (get m c) (assoc! m c []))
                              (.push (get m c) site)
                              m))
                          {} sites)]
    (.forEach (js/Object.values by-county) #(.sort % (fn [a b] (.localeCompare (:name a) (:name b)))))
    (assoc! state
            :dates (:dates data)
            :election (:election data)
            :sites sites
            :by-county by-county
            :counties (.sort (js/Object.keys by-county)))
    (set! (.-textContent ($ "updated"))
          (str "Election Day is " (d/fmt-date (:election data)) ". Early voting runs "
               (d/fmt-date (first (:dates data))) " – " (d/fmt-date (last (:dates data))) "."))
    (init-county-input!)
    (init-address-input!)
    (init-events!)
    (apply-url!)
    (.add (.-classList js/document.body) "ready")
    (sm/when-leaflet init-map!)))

;; Before the data loads, so a ?lang=es link starts translating right away
(translate/init ($ "lang") ($ "lang-note"))

(-> (js/fetch (.-href ($ "sites-data")))
    (.then #(.json %))
    (.then load!)
    (.catch (fn [e]
              (js/console.error e)
              (set! (.-hidden ($ "load-error")) false))))
