;;; -*- Clojure -*- mode
(ns sledge.core
  (:require-macros [cljs.core.async.macros :refer [go alt!]])
  (:import [goog.net XhrIo])
  (:require [goog.events :as events]
            [clojure.string :as string]
            [cljs.core.async :as async :refer [>! <! put! chan]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            ))

(enable-console-print!)

(defn mobile? [owner]
  (= (om/get-shared owner :device-type) :phone))

(def app-state
  (atom
    {:results []
     :player-queue []
     :filters {}
     :search-term ""
     :device-type nil
     }))

(defn mmss [seconds]
  (let [m (quot seconds 60)
        s (- seconds (* 60 m))]
    (str m ":" (.substr (str "000" s) -2))))


#_(println (mmss 20) (mmss 40) (mmss 60) (mmss 80)
         (mmss 200) (mmss 4000) (mmss 6000) (mmss 381))

(defn results-track-view [track owner]
  (reify
    om/IRenderState
    (render-state [this {:keys [enqueue update-filters]}]
      (let [artist (dom/span
                    #js {:className "artist"
                         :onClick #(put! update-filters
                                         {:artist (get @track "artist")})}
                    (get track "artist"))
            album (dom/span
                   #js {:className "album"
                        :onClick #(put! update-filters
                                        {:artist (get @track "artist")
                                         :album (get @track "album")})}
                   (get track "album" ))
            title (dom/span #js {:className "title"}
                            (str (get track "track") " - " (get track "title")))
            duration (dom/span #js {:className "duration"} (mmss (get track "length")))
            button (dom/button #js {:onClick (fn [e] (put! enqueue @track))}
                               "+")]
        (apply dom/div #js {:className "track"}
               (if (mobile? owner)
                 [title artist album duration button]
                 [artist album title duration button]))))))

(defn results-view [app owner]
  (reify
    om/IWillMount
    (will-mount [_]
      (let [channel (om/get-state owner :new-results)]
        (go (loop []
              (let [tracks (<! channel)
                    sorted (sort-by
                            #(vector (get % "artist")
                                     (get % "album")
                                     (js/parseInt (get % "track")))
                            tracks)]
                (om/update! app :results (vec sorted))
                (recur))))))
    om/IRenderState
    (render-state [this {:keys [enqueue update-filters]}]
      (let [button (dom/button
                    #js {:onClick
                         (fn [e] (doall (map #(put! enqueue %)
                                             (:results @app))))}
                    "+")
            track-components
            (om/build-all results-track-view (:results app)
                          {:init-state
                           {:update-filters update-filters
                            :enqueue enqueue}})]
        (if (mobile? owner)
          (apply dom/div #js {:className "results tracks" }
                 (dom/div #js {:className "track"}
                          (dom/span {:id "queue-all-tracks"}
                                    "Queue all tracks")
                          button)
                 track-components)
          (apply dom/div #js {:className "results tracks" }
                 (dom/div #js {:className "track header"}
                          (dom/span #js {:className "artist"} "Artist")
                          (dom/span #js {:className "album"} "Album" )
                          (dom/span #js {:className "title"} "Title")
                          (dom/span #js {:className "duration"} "Length")
                          button)
                 track-components))))
    ))


(defn queue-track-view [track owner]
  (reify
    om/IRenderState
    (render-state [this {:keys [dequeue]}]
      (dom/div #js {:className "track"}
               (dom/span #js {:className "artist"} (get track "artist"))
               (dom/span #js {:className "album"} (get track "album" ))
               (dom/span #js {:className "title"} (get track "title"))
               (dom/span #js {:className "duration"} (mmss (get track "length")))
               (dom/button #js {:onClick (fn [e] (put! dequeue @track))}
                           "-")))))

(defn queue-view [app owner]
  (reify
    om/IWillMount
    (will-mount [_]
      (let [enqueue (om/get-state owner :enqueue)
            dequeue (om/get-state owner :dequeue)]
        (go (loop []
              (alt!
               enqueue ([track]
                          (om/transact! app :player-queue #(conj % track)))
               ;; XXX dequeue really needs to work by position not contents
               ;; so it can deal with duplicate playlist entries
               dequeue ([track]
                          (om/transact! app :player-queue
                                        (fn [v] (vec (remove #(= track %)
                                                             v))))))
              (recur)))))
    om/IRenderState
    (render-state [this {:keys [dequeue]}]
      (apply dom/div #js {:className "queue tracks"}
             (dom/div #js {:className "track header"}
                      (dom/span #js {:className "artist"} "Artist")
                      (dom/span #js {:className "album"} "Album" )
                      (dom/span #js {:className "title"} "Title")
                      (dom/span #js {:className "duration"} "Length")
                      (dom/button #js {:onClick
                                       (fn [e] (doall (map #(put! dequeue %)
                                                           (:player-queue @app))))}
                                  "-"))
             (om/build-all queue-track-view (:player-queue app)
                           {:init-state {:dequeue dequeue}})
             ))))

(defn query-string-for-map [h]
  (string/join "&"
            (map (fn [[k v]] (str (name k) "=" v)) h)))

(defn send-query [term filters channel]
  (.send XhrIo (string/join "?" ["/tracks.json" (query-string-for-map filters)])
         (fn [e]
           (let [xhr (.-target e)
                 o (.getResponseJson xhr)
                 r (js->clj o)]
             (put! channel r)))
         "POST"
         term
         {"Content-Type" "text/plain"}
         ))

(defn send-search-xhr [term owner {:keys [new-results search-term]}]
  (let [filters (:filters @app-state)]
    (om/set-state! owner :search-term term)
    (send-query term filters new-results)))

(defn filters-view [app owner]
  (reify
    om/IWillMount
    (will-mount [_]
      (let [channel (om/get-state owner :update-filters)]
        (go (loop []
              (let [new-filter (<! channel)]
                (println [:new-filter new-filter])
                (om/transact! app [:filters] #(merge % new-filter))
                (send-query
                 (om/get-state owner :search-term)
                 (:filters @app)
                 (om/get-state owner :new-results))
                (recur))))))
    om/IRenderState
    (render-state [this state]
      (let [chan (:update-filters state)
            filters (:filters app)]
        (dom/div nil
                 (dom/h2 nil "search")
                 (dom/input #js {:ref "search-term"
                                 :id "search-term"
                                 :type "text"
                                 :value (:search-term state)
                                 :onChange #(send-search-xhr (.. % -target -value) owner state)
                                 })

                 (apply dom/div #js {:className "filters" }
                        (map #(dom/span #js {:className "filter"
                                             :onClick
                                             (fn [e] (put! chan
                                                           {(first %) nil}))}
                                        (str (name (first %)) ": " (second  %)))
                             (filter second filters))))))))

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
        (.addEventListener el
                           "ended"
                           #(put! (om/get-state owner :dequeue)
                                  (first (:player-queue @app)))
                           true)))
    om/IRenderState
    (render-state [this state]
      (let [bits (best-media-url (first (:player-queue app)))]
        (dom/div nil
                 (dom/audio #js {:controls true
                                 :autoPlay true
                                 :ref "player"
                                 :src bits
                                 })
                 )))))

(defn detect-platform []
  (let [w (.-innerWidth js/window)]
    (condp > w
      480 :phone
      600 :tablet
      :desktop)))

(defn app-view [app owner]
  (reify
    om/IInitState
    (init-state [_]
      {:enqueue (chan)
       :search-term ""
       :new-results (chan)
       :update-filters (chan)
       :dequeue (chan)})
    om/IRenderState
    (render-state [this state]
      (dom/div nil
               (om/build filters-view app
                         {:init-state state})
               (dom/h2 nil "results")
               (om/build results-view app {:init-state state})
               (dom/h2 nil "queue")
               (om/build queue-view app {:init-state state})
               (om/build player-view app {:init-state state})
               ))))

(defn init []
  (let [el (. js/document (getElementById "om-app"))]
    (om/root app-view app-state
             {:target el
              :shared {:device-type
                       (condp > (.-innerWidth js/window)
                         480 :phone
                         600 :tablet
                         :desktop)}})))

(.addEventListener js/window "load" init)
