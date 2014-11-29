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

(def app-state
  (atom
    {:results []
     :player-queue []
     :search-term ""
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
    (render-state [this {:keys [enqueue]}]
      (dom/div #js {:className "track"}
               (dom/span #js {:className "artist"} (get track "artist"))
               (dom/span #js {:className "album"} (get track "album" ))
               (dom/span #js {:className "title"}
                         (str (get track "track") " - " (get track "title")))
               (dom/span #js {:className "duration"} (mmss (get track "length")))
               (dom/button #js {:onClick (fn [e] (put! enqueue @track))}
                           "+")))))

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
    (render-state [this {:keys [enqueue]}]
      (apply dom/div #js {:className "results tracks" }
             (dom/div #js {:className "track header"}
                      (dom/span #js {:className "artist"} "Artist")
                      (dom/span #js {:className "album"} "Album" )
                      (dom/span #js {:className "title"} "Title")
                      (dom/span #js {:className "duration"} "Length")
                      (dom/button #js {:onClick
                                       (fn [e] (doall (map #(put! enqueue %)
                                                           (:results @app))))}
                                  "+"))
             (om/build-all results-track-view (:results app)
                           {:init-state {:enqueue enqueue}})
             ))))

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

(defn handle-change [e owner {:keys [new-results search-term]}]
  (let [term (.. e -target -value)]
    (om/set-state! owner :search-term term)
    (.send XhrIo (str "/tracks.json?artist=" term)
           (fn [e]
             (let [xhr (.-target e)
                   o (.getResponseJson xhr)
                   r (js->clj o)]
               (put! new-results r)))
           "GET")))

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

(defn app-view [app owner]
  (reify
    om/IInitState
    (init-state [_]
      {:enqueue (chan)
       :search-term ""
       :new-results (chan)
       :dequeue (chan)})
    om/IRenderState
    (render-state [this state]
      (let [bits (best-media-url (first (:player-queue app)))]
        (dom/div nil
                 (dom/h2 nil "search")
                 (dom/input #js {:ref "search-term"
                                 :id "search-term"
                                 :type "text"
                                 :value (:search-term state)
                                 :onChange #(handle-change % owner state)
                                 })
                 (dom/h2 nil "results")
                 (om/build results-view app {:init-state state})
                 (dom/h2 nil "queue")
                 (om/build queue-view app {:init-state state})
                 (om/build player-view app {:init-state state})
                 )))))

(defn init []
  (let [el (. js/document (getElementById "om-app"))]
    (om/root app-view app-state
             {:target el})))


(.addEventListener js/window "load" init)
