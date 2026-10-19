(ns combobox
  "An accessible type-ahead (ARIA 1.2 combobox) shared by the county and
  address inputs.")

(defn highlight
  "`text` as #html with the first case-insensitive match of `q` in a <mark>.
  Like every #html helper it returns an Html value, since a `str`ed one gets
  escaped when nested in another #html."
  [text q]
  (let [q (.trim (or q ""))
        i (if (seq q) (.indexOf (.toLowerCase text) (.toLowerCase q)) -1)]
    (if (neg? i)
      #html [:<> text]
      #html [:<> (.slice text 0 i)
             [:mark (.slice text i (+ i (count q)))]
             (.slice text (+ i (count q)))])))

(defn combobox
  "Wires `input` and its `list` (a <ul role=listbox>) together.

  Options:
  - `:source` - `(fn [q])` returning items, or a promise of them
  - `:render` - `(fn [item q])` returning an item's inner #html
  - `:on-select` - `(fn [item])` when an item is picked
  - `:min-chars` - characters needed before searching. 0 lists on focus
  - `:delay` - debounce in ms, for network sources
  - `:empty` - #html shown when nothing matches

  Returns `{:close (fn [])}`."
  [{:keys [input list source render on-select min-chars delay empty]}]
  (let [st {:items [] :active -1 :seq 0 :timer nil}
        id (.-id list)
        close! (fn []
                 (set! (.-hidden list) true)
                 (.setAttribute input "aria-expanded" "false")
                 (.removeAttribute input "aria-activedescendant"))
        set-active! (fn [i]
                      (assoc! st :active i)
                      (.forEach (.querySelectorAll list "[role=option]")
                                (fn [li k]
                                  (.setAttribute li "aria-selected" (str (= k i)))
                                  (when (= k i)
                                    (.setAttribute input "aria-activedescendant" (.-id li))
                                    (.scrollIntoView li {:block "nearest"})))))
        show! (fn [items]
                (let [q (.-value input)]
                  (assoc! st :items items)
                  (set! (.-innerHTML list)
                        (if (seq items)
                          (.join (.map items (fn [item i]
                                               (str #html [:li {:id (str id "-" i) :role "option"
                                                                :data-i i :aria-selected "false"}
                                                           (render item q)])))
                                 "")
                          (str #html [:li.combo-empty {:role "presentation"} empty])))
                  (set! (.-hidden list) false)
                  (.setAttribute input "aria-expanded" "true")
                  (set-active! (if (and (seq items) (seq (.trim q))) 0 -1))))
        update! (fn []
                  (let [q (.-value input)
                        n (inc (:seq st))]
                    (assoc! st :seq n)
                    (if (< (count (.trim q)) min-chars)
                      (close!)
                      (.then (js/Promise.resolve (source q))
                             (fn [items]
                               ;; Drop slow responses for text the user has since changed
                               (when (and (= n (:seq st)) (= js/document.activeElement input))
                                 (show! items)))))))
        choose! (fn [i]
                  (when-let [item (aget (:items st) i)]
                    (close!)
                    (on-select item)))]
    (.addEventListener input "input"
                       (fn []
                         (js/clearTimeout (:timer st))
                         (assoc! st :timer (js/setTimeout update! (or delay 0)))))
    (.addEventListener input "focus"
                       (fn []
                         (when (zero? min-chars)
                           (.select input)
                           (update!))))
    (.addEventListener input "blur" (fn [] (js/setTimeout close! 150)))
    (.addEventListener input "keydown"
                       (fn [e]
                         (let [n (count (:items st))
                               open? (not (.-hidden list))]
                           (case (.-key e)
                             "ArrowDown" (do (.preventDefault e)
                                             (if open?
                                               (set-active! (mod (inc (:active st)) (max n 1)))
                                               (update!)))
                             "ArrowUp" (do (.preventDefault e)
                                           (when open?
                                             (set-active! (mod (dec (+ n (:active st))) (max n 1)))))
                             "Enter" (when (and open? (pos? n))
                                       (.preventDefault e)
                                       (choose! (max 0 (:active st))))
                             "Escape" (when open?
                                        (.preventDefault e)
                                        (close!))
                             nil))))
    ;; Keep focus in the input so blur doesn't close the list before the click
    (.addEventListener list "mousedown" #(.preventDefault %))
    (.addEventListener list "click"
                       (fn [e]
                         (when-let [li (.closest (.-target e) "[role=option]")]
                           (choose! (js/Number (.-i (.-dataset li)))))))
    {:close close!}))
