(ns sledge.core
  (:require-macros [cljs.core.async.macros :refer [go alt!]])
  (:import [goog.net XhrIo] goog.Uri)
  (:require [goog.events :as events]
            [cljsjs.react :as React]
            [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as string]
            [clojure.set :as set]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :as async :refer [>! <! put! chan]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            ))

(enable-console-print!)

;; we need to be a whole lot clearer about whether we're playing,
;; what we're playing (now/next), and why we're not playing

;; so we have desired state ("we are pointing at queue entry 3 and
;; 'play' has been activated")
;; and actual state ("we have played 34s of the track with url ...; we
;; ran out of data and now the player is paused")

;; also: whether we *want* to be in 'play' or 'pause' has no bearing
;; on whether there is anything playable in the queue

;; when the player is playing it is sending us messages about the
;; current play time.  when we use the 'skip track' button we also
;; have to tell the player to change track. so there's information
;; flowing both ways there
;;


(def app-state
  (atom
    {:search {
              :term #{}
              :results []
              }
     :queue {
             :tracks []
             :next-track 0 ; now-playing (dec next-track)
             }
     :player-queue []  ; do not use
     :viewing-queue? false
     :player {:track-number 0
              :playing true
              :track-offset 0
              }
     }))

(defn empty-queue [] {:tracks [] :next-track 0})

(defn queue-current-entry
  "Return the current entry in the queue, or nil if empty"
  [queue]
  (let [tracks (:tracks queue)
        nxt (:next-track queue)]
    (if (seq tracks)
      (if (zero? nxt)
        (first tracks)                  ; dubious
        (nth tracks (dec nxt))))))

(deftest queue-current-test
  (is (nil? (queue-current-entry (empty-queue))))
  (let [q {:tracks [:a :b :c :d] :next-track 2}]
    (is (= (queue-current-entry q) :b))
    (is (= (queue-current-entry {:tracks [] :next-track 0}) nil))))


(defn queue-empty?
  "Have we run out of tracks to play when the current track (if any) is done?"
  [queue]
  (>= (:next-track queue) (count (:tracks queue))))

(defn queued-track
  [queue]
  (if (not (queue-empty? queue))
    (nth (:tracks queue) (:next-track queue))))

(deftest queue-empty-test
  (is (queue-empty? (empty-queue)))
  (is (not (queue-empty? {:tracks [:a] :next-track 0})))
  (is (queue-empty? {:tracks [:a] :next-track 1}))
  (let [q {:tracks [:a :b :c :d] :next-track 0}]
    (is (= (queued-track q) :a))
    (is (not (queue-empty? q)))))

(defn advance-queue [queue]
  (if (queue-empty? queue)
    queue
    (update-in queue [:next-track] inc)))

(deftest advance-queue-test
  (let [q {:tracks [:a :b :c :d] :next-track 0}
        q1 (advance-queue q)
        q2 (-> q1 advance-queue advance-queue advance-queue advance-queue)]
    (is (= (:next-track q1) 1))
    (is (nil? (queued-track q2) ))
    (is (= (queued-track q2) (queued-track (advance-queue q2))))
    (is (= (:tracks q1) (:tracks q)))))


(defn remove-from-queue [queue track]
  (if (some #{track} (subvec (:tracks queue) (:next-track queue)))
    (update-in queue [:tracks] (partial remove #{track}))
    queue))

(deftest remove-from-queue-test
  (let [q {:tracks [:a :b :c :d] :next-track 2}]
    ;; can only remove unplayed tracks from queue
    (is (= (remove-from-queue q :c) {:tracks [:a :b :d] :next-track 2}))
    (is (= (remove-from-queue q :a) q)))
  (let [q (empty-queue)]
    ;; ok to remove a track that's not there
    (is (= (remove-from-queue q :foo) q))))


(defn enqueue-track [queue track]
  (update-in queue [:tracks] conj track))

(defn search-results []
  (om/ref-cursor (:results (:search (om/root-cursor app-state)))))

(defn player-queue []
  (om/ref-cursor (:player-queue (om/root-cursor app-state))))

(defn player-state []
  (om/ref-cursor (:player (om/root-cursor app-state))))

(defn player-pause []
  (om/transact! (player-state) #(update-in % [:playing] not)))

(defn current-track []
  (nth (player-queue) (:track-number (player-state)) nil))

(defn player-next []
  ;; how do we make this fail if there are no more tracks?
  (om/transact! (player-state) #(update-in % [:track-number] inc)))

(defn player-playing [e]
  (let [player (.-target e)
        offset (.-currentTime player)]
    (om/transact! (player-state)
                  #(update-in % [:track-offset]
                              (fn [time] offset)))))

(defn player-prev []
  (let [dec0 #(max (dec %) 0)]
    (om/transact! (player-state) #(update-in % [:track-number] dec0))))


(defn player-el []
  (aget (.getElementsByTagName js/document "audio") 0))

(defn enqueue-track [track]
  (om/transact! (player-queue) #(conj % track)))

(defn dequeue-track [index]
  (om/transact! (player-queue)
                (fn [v] (vec (concat (subvec v 0 index)
                                     (subvec v (inc index)))))))
(deftest enqueue-test
  (let [q {:tracks [:a :b :c :d] :next-track 2}]
    (is (= (:tracks (enqueue-track q :z)) [:a :b :c :d :z]))))

(defn dequeue-all []
  (om/transact! (player-queue) (fn [v] [])))

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


(defn results-track-view [track owner]
  (reify
    om/IRender
    (render [this]
      (let [search-chan (om/get-shared owner :search-channel)
            artist (dom/span
                    #js {:className "artist"
                         :onClick #(put! search-chan
                                         [:add
                                          [[:artist (get @track "artist")]]])}
                    (get track "artist"))
            album (dom/span
                   #js {:className "album"
                        :onClick #(put! search-chan
                                        [:add
                                         [[:artist (get @track "artist")]
                                          [:album (get @track "album")]]])}
                   (get track "album"))
            title (dom/span #js {:className "title"}
                            (str (get track "track") " - " (get track "title")))
            duration (dom/span #js {:className "duration"} (mmss (get track "length")))
            button (dom/button #js {:onClick #(enqueue-track @track)} "+")]
        (apply dom/div #js {:className "track"}
               [title artist album duration button]
               )))))


(defn xhr-search [term]
  (let [term (filter second term)
        json-term (apply vector "and"
                         (map (fn [[k v]] ["like" (name k) v]) term))
        body (.stringify js/JSON (clj->js json-term))
        channel (chan)]
    (.send XhrIo "/tracks.json"
           (fn [e]
             (let [xhr (.-target e)
                   code (.getStatus xhr)
                   o (and (< code 400) (js->clj (.getResponseJson xhr)))]
               (put! channel (or o []))))
           "POST"
           body
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
      (let [button (dom/button
                    #js {:onClick
                         (fn [e] (doall (map #(enqueue-track %)
                                             tracks)))}
                    "+")
            track-components
            (om/build-all results-track-view tracks)]
        (apply dom/div #js {:className "results tracks" }
               (dom/div #js {:className "track"}
                        (dom/span #js {:id "queue-all-tracks"}
                                  "Queue all tracks")
                        button)
               track-components)
        ))
    ))


(defn queue-track-view [track owner]
  (reify
    om/IRenderState
    (render-state [this {:keys [index current?]}]
      (dom/div #js {:className (if current? "current-track track" "track")}
               (dom/span #js {:className "artist"} (get track "artist"))
               (dom/span #js {:className "album"} (get track "album" ))
               (dom/span #js {:className "title"} (get track "title"))
               (dom/span #js {:className "duration"} (mmss (get track "length")))
               (dom/button #js {:onClick #(dequeue-track index)}
                           "-")))))

(defn audio-el [state owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (let [el (om/get-node owner)]
        ;; last arg "true" is cos audio events don't bubble
        ;; http://stackoverflow.com/questions/11291651/why-dont-audio-and-video-events-bubble
        (.addEventListener el "ended" player-next true)
        (.addEventListener el "timeupdate" player-playing true)))
    om/IRender
    (render [_]
      (html [:audio {:ref "player"}]))))

(defn swallowing [h]
  (fn [e]
    (let [r (h e)]
      (.stopPropagation e)
      r)))

(defn svg [& elements]
  (into
   [:svg {:xmlns "http://www.w3.org/2000/svg"
          :version "1.1"
          :width "24px"
          :height "20px"
          :viewBox "-10 -10 120 120"
          :xmlnsXlink "http://www.w3.org/1999/xlink"
          }]
   elements))

(defn polygon [& points]
  [:polygon {:className "button"
             :points (string/join " " points)
             :fill "#006765"}])

(defn svg-play []
  (svg [:g {:transform "translate(15 0)"}
        (polygon 0,0 80,50 0,100 0,0)]))

(defn svg-pause []
  (svg [:g {:transform "translate(15 0)"}
        (polygon 0,0 25,0 25,100 0,100 0,0)
        (polygon 40,0 65,0 65,100 40,100 40,0)]))

(defn svg-spinner []
  [:div {:className "spinning"}
   (svg
    [:circle {:cx 50 :cy 20 :r 11 :fill "#006765"}]
    [:circle {:cx 50 :cy 80 :r 11 :fill "#006765"}]
    [:circle {:cx 20 :cy 50 :r 11 :fill "#006765"}]
    [:circle {:cx 80 :cy 50 :r 11 :fill "#006765"}])])

(defn svg-skip-track [ & [backward?]]
  (svg [:g {:transform (if backward? "rotate(180 50 50)" "translate(0 0)")}
        (polygon 0,0 35,50 0,100 0,0)
        (polygon 40,0 80,50 40,100 40,0)
        (polygon 85,0 95,0 95,100 85,100 85,0)]))

(defn transport-buttons-view [app owner]
  (reify
    om/IRender
    (render [this]
      (let [queue (om/observe owner (player-queue))
            playing? (:playing (player-state))
            track (current-track)]
        (html
         [:div {:id "transport"
                :onClick #(om/transact! app [:viewing-queue?] not)
                }
          [:span {:className "index"}
           [:span {:className "current"}
            (inc (:track-number (player-state)))]
           "/"
           [:span {:className "total"}
            (count (player-queue))]]
          [:div {:className "title-artist"}
           [:span {:className "title"}
            (get track "title")]
           [:span {:className "artist"}
            (get track "artist")]
           [:span {:className "album"}
            (get track "album")]]
          [:span {:className "buttons"}
           [:button {:onClick (swallowing player-prev) }
            (svg-skip-track :backwards)]
           [:button {:onClick (swallowing player-pause) }
            (if playing? (svg-pause) (svg-play))]
           [:button {:onClick (swallowing player-next) }
            (svg-skip-track)]]
          [:span {:className "offset"}
           [:span {:className "elapsed-time time"}
            (mmss (:track-offset (player-state)))]
           " / "
           [:span {:className "track-time"}
            (mmss (get track "length" 0))]]
          ])))))

(defn queue-view [app owner]
  (reify
    om/IRender
    (render [this]
      (let [queue (om/observe owner (player-queue))]
        (html
         [:div {}
          [:div {:className "track header"}
           [:span {:className "artist"} "Delete queue"]
           [:button {:onClick #(dequeue-all)} "-"]]
          (map #(om/build queue-track-view
                          %1
                          {:state
                           {:index %2
                            :current? (= (-> app :player :track-number)
                                         %2)
                            }})
               queue (range 0 999))
          ])))))

(defn search-entry-view [term owner]
  (reify
    om/IRenderState
    (render-state [this state]
      (let [search-chan (om/get-shared owner :search-channel)
            send-search (fn [e]
                          (let [str (.. e -target -value)]
                            (if-not (empty? str)
                              (put! search-chan [:add [[:_content str]]]))))]
        (dom/div
         #js {:className "search-box"}
         (apply dom/span #js {:className "filters" }
                (map #(dom/span #js {:className "filter"
                                     :onClick
                                     (fn [e] (put! search-chan
                                                   [:drop [%]]))}
                                (str (name (first %)) ": " (second  %)))
                     (filter second term)))
         (dom/div #js {:id "bodge"}
                  (dom/input #js {:ref "search-term"
                                  :id "search-term"
                                  :type "text"
                                  :size "10"
                                  :placeholder "Search artist/album/title"
                                  :value (:string state)
                                  :onChange
                                  #(om/set-state! owner :string
                                                  (.. % -target -value))
                                  :onKeyUp
                                  #(when (= 13 (.-which %))
                                     (send-search %)
                                     (om/set-state! owner :string ""))
                                  :onBlur send-search
                                  })))
        ))))

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
    :drop (set/difference previous new-terms)))

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
    (render [this]
      (dom/div nil
               (om/build search-entry-view (:term search)
                         {:init-state {:string ""}})
               (om/build results-view (:results search))))))

#_
(defn print-debuggy-stuff []
  (let [p (player-el)]
    (println [(.-currentTime p)
              (.-duration p)
              (.-paused p)
              (.-networkState p)
              (.-ended p)
              (:track-number (player-state))
              ])))

(defn sync-transport [_ ref o n]
  (let [desired (:player n)
        actual (player-el)
        urls (queue-current-entry (:queue desired))]

    ;; find out what track we should be playing
    (let [bits (best-media-url urls)
          actual-path (.getPath (goog.Uri. (.-src actual)))]
      (when (and bits (not (= actual-path bits)))
        (set! (.-src actual) bits)))

    (when (and (.-paused actual) (:playing desired) urls)
      ;; it might have paused because it reached the end of track, or
      ;; because the user previously paused it.  If we have music available,
      ;; now, resume.
      ;; On Android, this appears to trigger the generation of an ended event
      ;; that might not otherwise get sent, which is nice.  However,
      (.play actual))

    (if-not (:playing desired)
      (.pause actual))))


(defn app-view [state owner]
  (reify
    om/IRender
    (render [this]
      (html
       [:div
        [:header {:className "default-primary-color" } "sledge"]
        (om/build search-view (:search state))
        (if (:viewing-queue? state)
          [:div {:className "queue queue-open tracks" }
           (om/build queue-view state)]
          [:div {:className "queue tracks" } " "])
        (om/build transport-buttons-view state)
        (om/build audio-el state)
        ]))))

(defn init []
  (add-watch app-state :transport sync-transport)
  (let [el (. js/document (getElementById "om-app"))
        search (chan)]
    (om/root app-view app-state
             {:target el
              :shared {:search-channel search}})))

(.addEventListener js/window "load" init)



(cljs.test/run-tests)
