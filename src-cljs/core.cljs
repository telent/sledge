;;; -*- Clojure -*- mode
(ns sledge.core
  (:require-macros [cljs.core.async.macros :refer [go alt!]])
  (:import [goog.net XhrIo])
  (:require [goog.events :as events]
            [clojure.string :as string]
            [clojure.set :as set]
            [cljs.core.async :as async :refer [>! <! put! chan]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [simple-brepl.client]
            ))

(enable-console-print!)

(def app-state
  (atom
    {:search {
              :term #{}
              :results []
              }
     :player-queue []
     :tab-on-view [:search]
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

(defn tab-on-view []
  (om/ref-cursor (:tab-on-view (om/root-cursor app-state))))

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
                        (dom/span {:id "queue-all-tracks"}
                                  "Queue all tracks")
                        button)
               track-components)
        ))
    ))


(defn queue-track-view [track owner]
  (reify
    om/IRenderState
    (render-state [this {:keys [index]}]
      (dom/div #js {:className "track"}
               (dom/span #js {:className "artist"} (get track "artist"))
               (dom/span #js {:className "album"} (get track "album" ))
               (dom/span #js {:className "title"} (get track "title"))
               (dom/span #js {:className "duration"} (mmss (get track "length")))
               (dom/button #js {:onClick #(dequeue-track index)}
                           "-")))))

(defn queue-view [app owner]
  (reify
    om/IRender
    (render [this]
      (let [queue (om/observe owner (player-queue))
            on-view (om/observe owner (tab-on-view))]
        (when (= (first on-view) :player-queue)
          (apply dom/div #js {:className "queue tracks"}
                 (dom/div #js {:className "track header"}
                          (dom/span #js {:className "artist"} "Delete queue")
                          (dom/button #js {:onClick #(dequeue-all)} "-"))
                 (map #(om/build queue-track-view
                                 %1
                                 {:state {:index %2}})
                      queue (range 0 999))
                 ))))))

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

(defn best-media-url [r]
  (let [urls (get r "_links")]
    (get
     (or (get urls "ogg") (get urls "mp3"))
     "href")))

(defn player-view [app owner]
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
      (let [queue (om/observe owner (player-queue))
            on-view (om/observe owner (tab-on-view))]
        (dom/span #js {}
                  (dom/button #js { :onClick player-pause }
                              ">")
                  (dom/button #js { :onClick player-next }
                              ">>|")
                  (dom/button #js { :onClick player-prev }
                              "|<<")
                  (dom/span #js {:className "elapsed-time time"}
                            (mmss (:track-offset (player-state))))
                  " / "
                  (dom/span #js {:className "track-time"}
                            (mmss (get (current-track) "length" 0)))
                  (dom/audio #js {:ref "player"}))))))


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
      (let [on-view (om/observe owner (tab-on-view))]
        (if (= (first on-view) :search)
          (dom/div nil
                   (om/build search-entry-view (:term search)
                             {:init-state {:string ""}})
                   (om/build results-view (:results search))))))))

(defn show-tab [cursor tab-name]
  (om/update! cursor [:tab-on-view] [tab-name]))

(defn tab-selector-view [app owner]
  (reify
    om/IRender
    (render [this]
      (let [on-view (om/observe owner (tab-on-view))]
        (dom/nav nil
                 (dom/ul nil
                         (dom/li
                          #js {:onClick #(show-tab app :search)
                               :className
                               (if (= (first on-view) :search)
                                 "selected"
                                 "unselected")
                               }
                          (dom/span #js {:id "show-library"} "library"))
                         (dom/li
                          #js {:onClick #(show-tab app :player-queue)
                               :className
                               (if (= (first on-view) :player-queue)
                                 "selected"
                                 "unselected")
                               }
                          (dom/span #js {:id "show-queue"} "queue"))))))))


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


(defn app-view [app owner]
  (reify
    om/IRender
    (render [this]
      (dom/div nil
               (dom/header
                #js {:id "sledge"}
                "sledge"
                (om/build tab-selector-view app))
               (dom/div #js {:className "scrolling"}
                        (om/build search-view (:search app))
                        (om/build queue-view app))
               (dom/footer #js {}
                           #_#_#_
                           (dom/span #js {:onClick print-debuggy-stuff }
                                     "debug")
                           "  "
                           (dom/span #js {:onClick sync-transport }
                                     "sync")
                           (om/build player-view app))
               ))))

(defn init []
  (add-watch app-state :transport sync-transport)
  (let [el (. js/document (getElementById "om-app"))
        search (chan)]
    (om/root app-view app-state
             {:target el
              :shared {:search-channel search}})))

(.addEventListener js/window "load" init)
