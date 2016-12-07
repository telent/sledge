(ns sledge.core
  (:require-macros [cljs.core.async.macros :refer [go alt!]])
  (:import [goog.net XhrIo] goog.Uri)
  (:require [goog.events :as events]
            [cljs.spec :as s :include-macros true]
            [cljs.spec.test :as stest]
            [cljs.test :refer-macros [deftest is testing]]
            [cljsjs.react :as React]
            [clojure.string :as string]
            [clojure.set :as set]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :as async :refer [>! <! put! chan]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            ))

(enable-console-print!)

(def app-state
  (atom
    {:search {
              :term #{}
              :results []
              }
     :player {
              :queue {
                      :tracks []
                      :current-track 0
                      }
              :want-play false
              :audio-el {:track-offset 0 :playing false :ready-state nil}
              }
     }))

(defn empty-queue [] {:tracks [] :current-track 0})

(defn queue-empty?
  "True if there is no music left and playback must stop until a track is added"
  [queue]
  (>= (:current-track queue) (count (:tracks queue))))

(deftest queue-empty-test
  (is (queue-empty? (empty-queue)))
  (is (not (queue-empty? {:tracks [:a] :current-track 0})))
  (is (queue-empty? {:tracks [:a] :current-track 1}))
  (let [q {:tracks [:a :b :c :d] :current-track 0}]
    (is (not (queue-empty? q)))))

(defn queue-current-entry
  "Return the current entry in the queue, or nil if at end"
  [queue]
  (if (queue-empty? queue)
    nil
    (let [tracks (:tracks queue)
          cur (:current-track queue)]
      (nth tracks cur))))

(deftest queue-current-test
  (is (nil? (queue-current-entry (empty-queue))))
  (let [q {:tracks [:a :b :c :d] :current-track 2}]
    (is (= (queue-current-entry q) :c))
    (is (= (queue-current-entry (empty-queue)) nil))
    (is (= (queue-current-entry {:tracks [:a] :current-track 0}) :a))
    (is (= (queue-current-entry {:tracks [:a] :current-track 1}) nil))))

(defn queued-track
  [queue]
  (if (not (queue-empty? queue))
    (nth (:tracks queue) (inc (:current-track queue)))))

(deftest queued-track-test
  (let [q {:tracks [:a :b :c :d] :current-track 0}]
    (is (= (queued-track q) :b))))

(defn advance-queue [queue]
  (if (queue-empty? queue)
    queue
    (update-in queue [:current-track] inc)))

(deftest advance-queue-test
  (let [q {:tracks [:a :b :c :d] :current-track 0}
        q1 (advance-queue q)
        q2 (-> q1 advance-queue advance-queue advance-queue advance-queue)]
    (is (= (:current-track q1) 1))
    (is (nil? (queued-track q2) ))
    (is (= (queued-track q2) (queued-track (advance-queue q2))))
    (is (= (:tracks q1) (:tracks q)))))

(defn enqueue-track [queue track]
  (update-in queue [:tracks] conj track))

(deftest enqueue-test
  (let [q (-> (empty-queue) (enqueue-track :a) (enqueue-track :b))]
    (is (= (queue-current-entry q) :a))
    (is (= (:tracks q) [:a :b]))))


(defn remove-from-queue [queue track]
  (if (and (not (queue-empty? queue))
           (some #{track} (subvec (:tracks queue) (inc (:current-track queue)))))
    (update-in queue [:tracks] (partial remove #{track}))
    queue))

(deftest remove-from-queue-test
  (let [q {:tracks [:a :b :c :d] :current-track 2}]
    ;; can only remove unplayed tracks from queue
    (is (= (remove-from-queue q :d) {:tracks [:a :b :c] :current-track 2}))
    (is (= (remove-from-queue q :c) q))
    (is (= (remove-from-queue q :a) q)))
  (let [q (empty-queue)]
    ;; ok to remove a track that's not there
    (is (= (remove-from-queue q :foo) q))))

(defn mmss [seconds]
  (let [m (quot seconds 60)
        s (- (Math/floor seconds) (* 60 m))]
    (str m ":" (.substr (str "000" s) -2))))

(deftest mmss-test
  (is (mmss 20) "0:20")
  (is (mmss 40) "0:40")
  (is (mmss 60) "1:00")
  (is (mmss 80) "1:20")
  (is (mmss 200) "3:20")
  (is (mmss 4000) "66:40")
  (is (mmss 6000) "100:00")
  (is (mmss 381) "6:21"))


(defn flex-track-listing [artist album title duration]
  [:div {:style {:flex-grow "1"
                 :display "inline-flex"
                 :flex-direction "column"}}
   [:div {:style {:display "inline-flex"
                  :justify-content "space-between"}}

    [:span {:style {:overflow "hidden"
                    :flex-wrap "nowrap"}}
     title]
    [:span {}
     duration ]]
   [:div {:style {:display "inline-flex"
                  :justify-content "space-between"
                  :font-size "90%"}}
    [:span {:style {:overflow "hidden"}}
     album]
    [:span {:style {:text-align "right"}}
     artist]]])

(defn results-track-view [track owner]
  (reify
    om/IRender
    (render [this]
      (let [search-chan (om/get-shared owner :search-channel)
            command-chan (om/get-shared owner :command-channel)
            artist [:span
                     {:onClick #(put! search-chan
                                      [:add
                                       [[:= "artist" (get @track "artist")]]])}
                    (get track "artist")]
            album [:span
                   {:onClick #(put! search-chan
                                    [:add
                                     [[:= "artist" (get @track "artist")]
                                      [:= "album" (get @track "album")]]])}
                   (get track "album")]
            title (get track "title")
            duration (mmss (get track "length"))
            add-button [:button {:onClick #(put! command-chan
                                                 [:enqueue @track])}
                        "+"]]
        (html [:div {:style {:display "flex"
                             :flex-direction "row"
                             :font-size "80%"
                             :margin-bottom "6px"
                             :border-bottom "#bbb dotted 1px"
                             }}
               (flex-track-listing
                artist album title duration)
               [:div {:style {:margin-left "0.5em"
                              :align-self "center"
                              :flex-grow "0"}}
                add-button]])
        ))))


(s/def ::search-term
  (s/or
   ::comparison (s/tuple #{:= :like} string? string?)
   ::shuffle (s/tuple #{:shuffle} string? )))

(deftest specs
  ;; these tests are here as experiments to see if I'm using
  ;; clojure.spec correctly, not as tests of clojure.spec
  (is (s/valid? ::search-term [:= "artist" "Lou Reed"]))
  (is (s/valid? ::search-term [:shuffle "Manic Thursday"])))

(defn search-term-as-js-obj [term]
  (let [json-term (apply vector "and" term)]
    (clj->js json-term)))

(s/fdef search-term-as-js-obj
        :args (s/cat :term (s/coll-of ::search-term))
        :ret string?)

(deftest search-term-test
  (is (thrown? js/Error
               (search-term-as-js-obj [[:fgh 7 "artist" "Queen"]])))
  (is (= (js->clj (search-term-as-js-obj [[:like "artist" "Queen"]]))
         ["and"  ["like" "artist" "Queen"]])))

(defn xhr-search [term]
  (let [channel (chan)]
    (.send XhrIo "/tracks.json"
           (fn [e]
             (let [xhr (.-target e)
                   code (.getStatus xhr)
                   o (and (< code 400) (js->clj (.getResponseJson xhr)))]
               (put! channel (or o []))))
           "POST"
            (.stringify js/JSON (search-term-as-js-obj term))
           {"Content-Type" "text/plain"}
           )
    channel))

(defn sorted-tracks [tracks]
  (sort-by
   #(vector (get % "artist")
            (get % "album")
            (js/parseInt (get % "track")))
   tracks))

(defn results-view [tracks owner]
  (reify
    om/IRender
    (render [this]
      (let [command-chan (om/get-shared owner :command-channel)
            button (dom/button
                    #js {:onClick
                         (fn [e] (doall
                                  (map #(put! command-chan [:enqueue %])
                                       tracks)))}
                    "+")
            track-components
            (om/build-all results-track-view tracks {:key "pathname"} )]
        (apply dom/div #js {:className "results tracks" }
               (dom/div #js {:className "track"}
                        (dom/span #js {:id "queue-all-tracks"}
                                  "Queue all tracks")
                        button)
               track-components)
        ))))


(defn update-audio-time [state event]
  (let [target (.-target event)
        time (Math/floor (.-currentTime target))]
    ;; don't update! on timeupdate unless the state has actually changed
    (if-not (= (:time-offset @state) time)
      (om/update! state [:time-offset] time))))

(defn audio-el [state owner]
  (reify
    om/IRender
    (render [this]
      (let [command-chan (om/get-shared owner :command-channel)]
        (html
         [:audio {:ref "player"
                  :loop false
                  :onTimeUpdate (partial update-audio-time state)
                  :onProgress #(om/update! state [:ready-state]
                                           (.-readyState (.-target %)))
                  :onEnded #(put! command-chan [:next-track])
                  }])))))

(defn svg [attrs & elements]
  (into
   [:svg (merge
          {:xmlns "http://www.w3.org/2000/svg"
           :version "1.1"
           :width "24px"
           :height "20px"
           :viewBox "-10 -10 120 120"
           :xmlnsXlink "http://www.w3.org/1999/xlink"
           }
          attrs)]
   elements))

(defn polygon [& points]
  [:polygon {:className "button"
             :points (string/join " " points)
             :fill "#006765"}])

(defn svg-play []
  (svg {}
       [:g {:transform "translate(15 0)"}
        (polygon 0,0 80,50 0,100 0,0)]))

(defn svg-pause []
  (svg {}
       [:g {:transform "translate(15 0)"}
        (polygon 0,0 25,0 25,100 0,100 0,0)
        (polygon 40,0 65,0 65,100 40,100 40,0)]))

(defn svg-spinner []
  [:div {:className "spinning"}
   (svg
    {}
    [:circle {:cx 50 :cy 20 :r 11 :fill "#006765"}]
    [:circle {:cx 50 :cy 80 :r 11 :fill "#006765"}]
    [:circle {:cx 20 :cy 50 :r 11 :fill "#006765"}]
    [:circle {:cx 80 :cy 50 :r 11 :fill "#006765"}])])

(defn svg-skip-track [ & [backward?]]
  (svg {}
       [:g {:transform (if backward? "rotate(180 50 50)" "translate(0 0)")}
        (polygon 0,0 35,50 0,100 0,0)
        (polygon 40,0 80,50 40,100 40,0)
        (polygon 85,0 95,0 95,100 85,100 85,0)]))

(defn svg-shuffle [attrs]
  (svg
   (merge {:viewBox "0 0 1000 1000"} attrs)
   [:path {:d "M64.6,309.4h190.5c40.1,0,75.9,21.3,100.8,54.5c17.7-32.9,39.7-63.3,64.8-90.5c-43.7-44.8-101.6-72.9-165.6-72.9H64.6c-30.1,0-54.5,24.4-54.5,54.5S34.5,309.4,64.6,309.4z"}]
   [:path {:d "M483,478.8c30-90.2,122.8-169.4,198.7-169.4h100l-70.4,70.4c-21.3,21.3-21.3,55.7,0,77c10.6,10.6,24.6,15.9,38.5,15.9s27.8-5.3,38.5-15.9L990,255L788.2,53.2c-21.3-21.3-55.7-21.3-77,0c-21.3,21.3-21.3,55.7,0,77l70.4,70.4h-100c-123.8,0-256.5,107.1-302,243.8l-25.7,76.8C319,626.2,241.1,690.6,200.6,690.6H64.5C34.3,690.6,10,715,10,745.1c0,30.1,24.3,54.5,54.5,54.5h136.1c100,0,210.3-104.8,256.6-243.8l25.7-76.8L483,478.8z"}]
   [:path {:d "M711.3,543.1c-21.3,21.3-21.3,55.7,0,77l70.4,70.4H654.4c-69,0-126.8-48.5-146.5-114.7c-14,40.8-34.1,81.6-59.4,119c48.3,63.3,122.1,104.7,205.9,104.7h127.3l-70.4,70.4c-21.3,21.3-21.3,55.7,0,77c10.6,10.6,24.6,15.9,38.5,15.9s27.8-5.3,38.5-15.9L990,745.1L788.2,543.3c-21.3-21.3-55.7-21.3-77,0L711.3,543.1z"}]
   ))


(defn player-el []
  (aget (.getElementsByTagName js/document "audio") 0))

(defn transport-elapsed-view [audio-el owner opts]
  (reify
    om/IRender
    (render [this]
      (let [track-length (:track-length opts)]
        (html
         [:span {:className "elapsed"}
          [:span {:className "elapsed-time time"}
           (mmss (:time-offset audio-el))]
          " / "
          [:span {:className "track-time time"}
           (mmss track-length)]]
         )))))

(defn swallowing [h]
  (fn [e]
    (let [r (h e)]
      (.stopPropagation e)
      r)))

(defn transport-buttons-view [state owner opts]
  (reify
    om/IRender
    (render [this]
      (let [command-chan (om/get-shared owner :command-channel)
            player-state (:audio-el state)
            playing (cond
                      (and (if-let [r (:ready-state player-state)] (< r 4))
                           (:want-play state))
                      :pending
                      (:want-play state)
                      true
                      :else
                      false)]
        (html
         [:span {:className "buttons"}
          [:button {:onClick
                    (swallowing #(put! command-chan [:previous-track]))}
           (svg-skip-track :backwards)]
          [:button {:onClick
                    (swallowing #(put! command-chan [:toggle-pause]))}
           (case playing
             true (svg-pause)
             :pending (svg-spinner)
             false (svg-play))]
          [:button {:onClick
                    (swallowing #(put! command-chan [:next-track]))}
           (svg-skip-track)]])))))

(defn transport-index-view [queue owner opts]
  (reify
    om/IRender
    (render [this]
      (let [track (queue-current-entry queue)]
        (html
         [:span {:className "index"}
          [:span {:className "current"} (inc (:current-track queue))]
          "/"
          [:span {:className "total"} (count (:tracks queue))]])))))

(defn transport-track-view [queue owner opts]
  (reify
    om/IRender
    (render [this]
      (let [track (queue-current-entry queue)]
        (html
         [:span {:className "title-artist"}
          [:span {:className "title"}
           (get track "title")]
          [:span {:className "artist"}
           (get track "artist")]
          [:span {:className "album"}
           (get track "album")]])))))

(defn transport-strip-view [wanted owner]
  (reify
    om/IRender
    (render [this]
      (let [queue (:queue wanted)
            track (queue-current-entry queue)]
        (if track
          (html
           [:div {:id "transport"
                  :onClick #(om/transact! wanted [:viewing-queue?] not)
                  }
            (om/build transport-index-view queue)
            (om/build transport-track-view queue)
            [:span {:className "right"}
             (om/build transport-elapsed-view (:audio-el wanted)
                       {:opts {:track-length (get track "length")}})
             (om/build transport-buttons-view wanted)
             ]])
          (html
           [:div {:id "transport"} "Choose tracks to add to play queue"]))))))


(defn queue-track-view [track owner]
  (reify
    om/IRenderState
    (render-state [this {:keys [index current?]}]
      (let [search-chan (om/get-shared owner :search-channel)
            command-chan (om/get-shared owner :command-channel)
            artist  (get track "artist")
            album (get track "album")
            title (get track "title")
            duration (mmss (get track "length"))
            remove-button [:button {:onClick #(put! command-chan
                                                    [:enqueue @track])}
                           "-"]]
        (html [:div {:style {:display "flex"
                             :background (if current? "red" "inherit")
                             :flex-direction "row"
                             :font-size "80%"
                             :margin-bottom "6px"
                             :border-bottom "#bbb dotted 1px"
                             }}
               (flex-track-listing
                artist album title duration)
               [:div {:style {:margin-left "0.5em"
                              :align-self "center"
                              :flex-grow "0"}}
                remove-button]])))))

(defn queue-view [queue owner]
  (reify
    om/IRender
    (render [this]
      (let [command-chan (om/get-shared owner :command-channel)]
        (html
         [:div {}
          [:div {:className "track header"}
           [:span {:className "artist"} "Delete queue"]
           [:button {:onClick #(put! command-chan [:delete-queue])}
            "-"]]
          (map #(om/build queue-track-view
                          %1
                          {:state
                           {:index %2
                            :current? (= (:current-track queue) %2)
                            }})
               (:tracks queue) (range 0 999))
          ])))))


(defmulti format-search-term (fn [op & terms] op))

(defmethod format-search-term :like [_ field value]
  (if (= field "_content")
    value
    (str field ": " value)))

(defmethod format-search-term := [_ field value]
  (str field ": " value))

(defmethod format-search-term :shuffle [_ name]
  (str "shuffle: " name))

(defn random-name []
  (let [c (count js/common_words)
        i1 (Math/floor (* (Math/random) c))
        i2 (Math/floor (* (Math/random) c))]
    (str (nth js/common_words i1) " " (nth js/common_words i2))))


(defn parse-search-term [string]
  (let [[term value] (string/split string #": *")]
    (if value
      (if (= term "shuffle")
        [:shuffle value]
        [:like term value])
      [:like "_content" string])))

(deftest parse-search-test
  (is (= (parse-search-term "hello") [:like "_content" "hello"]))
  (is (= (parse-search-term "artist: wibble fish")
         [:like "artist" "wibble fish"]))
  (is (= (parse-search-term "shuffle: clockwork orange")
         [:shuffle "clockwork orange"]))
  (is (= (parse-search-term "artist:noblank pshaw")
         [:like "artist" "noblank pshaw"])))

(defn search-entry-view [term owner]
  (reify
    om/IRenderState
    (render-state [this state]
      (let [search-chan (om/get-shared owner :search-channel)
            send-search
            (fn [e]
              (let [str (.. e -target -value)]
                (if-not (empty? str)
                  (put! search-chan [:add [(parse-search-term str)]]))))]
        (dom/div
         #js {:className "search-box"
              :onClick (fn [e]
                         (if-let [i (om/get-node owner "search-term")]
                           (.focus i)))}
         (apply dom/span #js {:className "filters" }
                (map #(dom/span #js {:className "filter"
                                     :onClick
                                     (swallowing
                                      (fn [e] (put! search-chan
                                                    [:drop [%]])))}
                                (apply format-search-term %))
                     term))
         (dom/span nil
                   (dom/input #js {:ref "search-term"
                                   :id "search-term"
                                   :type "text"
                                   :placeholder (if (seq term)
                                                  ""
                                                  "Search artist/album/title")
                                   :value (:string state)
                                   :onChange
                                   #(om/set-state! owner :string
                                                   (.. % -target -value))
                                   :onKeyUp
                                   #(when (= 13 (.-which %))
                                      (send-search %)
                                      (om/set-state! owner :string ""))
                                   :onBlur send-search
                                   }))) ))))

(defn can-play? [player media-type codec]
  (= "probably"
     (.canPlayType player
                   (if codec (str media-type "; codecs=" codec) media-type))))

(defn best-media-url [r]
  (let [urls (get r "_links")]
    (get
     (first (filter #(can-play? (player-el) (get % "type") (get % "codecs"))
                    (map (partial get urls) ["ogg" "mp3" "wma" "wav"])))
     "href")))

(defn update-term [[command new-terms] previous]
  (case command
    :add (set/union previous (set new-terms))
    :replace (set new-terms)
    :drop (set/difference previous new-terms)))

(s/fdef update-term
        :args (s/cat :action (s/tuple #{:add :replace :drop}
                                      (s/coll-of ::search-term))
                     :previous (s/coll-of ::search-term)))

(defn shuffle-button [term owner]
  (reify
    om/IRender
    (render [_]
      (let [channel (om/get-shared owner :search-channel)]
        (html [:div {:style {:margin-top "3em"
                             :textAlign "center"}
                     :onClick
                     (swallowing
                      #(put! channel [:replace [[:shuffle (random-name)]]]))
                     }
               [:p "Click the shuffle button to pick some random tracks"]
               (svg-shuffle {:width "4em" :height "4em"})])))))

(defn search-view [search owner]
  (reify
    om/IWillMount
    (will-mount [_]
      (let [channel (om/get-shared owner :search-channel)]
        (go (loop []
              (let [search-for (update-term (<! channel) (:term @search))
                    _ (om/update! search [:term] search-for)
                    tracks (<! (xhr-search search-for))
                    sorted (sorted-tracks tracks)]
                (om/update! search [:results] (vec sorted))
                (recur))))))
    om/IRender
    (render [_]
      (let [term (:term search)]
        (dom/div nil
                 (om/build search-entry-view term
                           {:init-state {:string ""}})
                 (if (zero? (count term))
                   (om/build shuffle-button term)
                   (om/build results-view (:results search))
                   ))))))


(defn sync-transport [_ ref o n]
  (when-let [actual (player-el)]
    (let [player-state (:player n)
          queue (:queue player-state)
          playable? (not (queue-empty? queue))
          track (and playable? (queue-current-entry queue))
          bits (and track (best-media-url track))
          actual-path (.getPath (goog.Uri. (.-src actual)))]

      (when (and bits (not (= actual-path bits)))
        (set! (.-src actual) bits))

      (when (and (.-paused actual) (:want-play player-state) track)
        (.play actual))

      (if-not (and playable? (:want-play player-state))
        (.pause actual)))))


(defn app-view [state owner]
  (reify
    om/IRender
    (render [this]
      (html
       [:div
        [:header {:className "default-primary-color" } "sledge"]
        (om/build search-view (:search state))
        (if (:viewing-queue? (:player state))
          [:div {:className "queue queue-open tracks" }
           (om/build queue-view (:queue (:player state)))]
          [:div {:className "queue tracks" } " "])
        (om/build transport-strip-view (:player state))
        (om/build audio-el (:audio-el (:player state)))
        ]))))

(defmulti dispatch-command (fn  [val command & args] command))

(defmethod dispatch-command :enqueue [val _ track]
  (update-in val [:player :queue] enqueue-track track))

(defmethod dispatch-command :toggle-pause [val _]
  (update-in val [:player :want-play] not))

(defmethod dispatch-command :next-track [val _]
  (update-in val [:player :queue] advance-queue))

;; still to add: [dequeue previous-track delete-queue]


(defn command-loop []
  (let [ch (chan)]
    (go
      (loop []
        (let [command (<! ch)]
          (swap! app-state #(apply dispatch-command % command))
          (recur))))
    ch))

(defn init []
  (add-watch app-state :transport sync-transport)
  (let [el (. js/document (getElementById "om-app"))
        command-chan (command-loop)
        search (chan)]
    (om/root app-view app-state
             {:target el
              :shared {:command-channel command-chan
                       :search-channel search}})
    (swap! app-state assoc-in [:player :want-play] true)))

(.addEventListener js/window "load" init)
(stest/instrument)
(cljs.test/run-tests)
