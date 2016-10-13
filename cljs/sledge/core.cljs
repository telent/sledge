(ns sledge.core
  (:require-macros [cljs.core.async.macros :refer [go alt!]])
  (:import [goog.net XhrIo] goog.Uri)
  (:require [goog.events :as events]
            [cljsjs.react :as React]
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

(def app-state
  (atom
    {:search {
              :term #{}
              :results []
              }
     :player-queue []
     :viewing-queue? false
     :player {:track-number 0
              :playing true
              :track-offset 0
              :track-offset-when 0
              }
     }))

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

(defn dequeue-all []
  (om/transact! (player-queue) (fn [v] [])))

(defn mmss [seconds]
  (let [m (quot seconds 60)
        s (- (Math/floor seconds) (* 60 m))]
    (str m ":" (.substr (str "000" s) -2))))


#_(println (mmss 20) (mmss 40) (mmss 60) (mmss 80)
         (mmss 200) (mmss 4000) (mmss 6000) (mmss 381))

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

(defn audio-el [app owner]
  (reify
    om/IDidMount
    (did-mount [this]
      (let [el (om/get-node owner)]
        ;; last arg "true" is cos audio events don't bubble
        ;; http://stackoverflow.com/questions/11291651/why-dont-audio-and-video-events-bubble
        (.addEventListener el "ended" player-next true)
        (.addEventListener el "timeupdate" player-playing true)))
    om/IRender
    (render [this]
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
          [:span {}
           [:span {:className "title-artist"}
            (get track "title") " - "
            (get track "artist")]]

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

(defn music-in-queue? [tracknum]
  (let [queue (player-queue)]
    (nth queue tracknum nil)))

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
        urls (music-in-queue? (:track-number desired))]

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
