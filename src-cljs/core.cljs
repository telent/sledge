;;; -*- Clojure -*- mode
(ns sledge.core
  (:require-macros [cljs.core.async.macros :refer [go alt!]])
  (:require [goog.events :as events]
            [clojure.string :as string]
            [cljs.core.async :as async :refer [>! <! put! chan]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            ))

(.log js/console "hello 2")

(enable-console-print!)

(def app-state
  (atom
    {:results
     [{"artist" "Orbital"
       "encoding-type" "mp3"
       "title" "Belfast"
       "album" "Live 1994-2004"
       "_links" {
                 "mp3" {"href" "http://localhost:53281/bits/L3Nydi9tZWRpYS9NdXNpYy9tcDMvYnktdGFnL29yYml0YWwvbGl2ZV9hdF9nbGFzdG9uYnVyeV8xOTk0LTIwMDQvMDQtYmVsZmFzdF9fMjAwMi5tcDM.mp3"}}
       }
      {
       "encoding-type" "mp3",
       "_links" {
                 "mp3" {
                        "href" "/bits/L3Nydi9tZWRpYS9NdXNpYy9tcDMvYnktdGFnL29yYml0YWwvYmx1ZV9hbGJ1bS8wMi1hY2lkX3BhbnRzLm1wMw.mp3"
                        }
                 },
       "artist" "Orbital",
       "title" "Acid Pants",
       "year" "2004",
       "album" "Blue Album",
       "track" "2"
       },]
     :player-queue []
     :search-term "artist: orbital"
     }))

(defn results-track-view [track owner]
  (reify
    om/IRenderState
    (render-state [this {:keys [enqueue]}]
      (dom/div #js {:className "track"}
               (dom/span #js {:className "title"} (get track "title"))
               (dom/span #js {:className "artist"} (get track "artist"))
               (dom/span #js {:className "album"} (get track "album" ))
               (dom/button #js {:onClick (fn [e] (put! enqueue @track))}
                           "+")))))

(defn results-view [app owner]
  (reify
    om/IRenderState
    (render-state [this {:keys [enqueue]}]
      (apply dom/div nil
             (om/build-all results-track-view (:results app)
                           {:init-state {:enqueue enqueue}})
             ))))

(defn queue-track-view [track owner]
  (reify
    om/IRenderState
    (render-state [this {:keys [dequeue]}]
      (dom/div #js {:className "track"}
               (dom/span #js {:className "title"} (get track "title"))
               (dom/span #js {:className "artist"} (get track "artist"))
               (dom/span #js {:className "album"} (get track "album" ))
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
      (apply dom/div nil
             (om/build-all queue-track-view (:player-queue app)
                           {:init-state {:dequeue dequeue}})
             ))))

(defn handle-change [e owner {:keys [search-term]}]
  (om/set-state! owner :search-term (.. e -target -value)))

(defn player-view [app owner]
  (reify
    om/IInitState
    (init-state [_]
      {:enqueue (chan)
       :search-term ""
       :dequeue (chan)})
    om/IRenderState
    (render-state [this state]
      (dom/div nil
               (dom/h2 nil "search")
               (dom/input #js {:ref "search-term"
                               :type "text"
                               :value (:search-term state)
                               :onChange #(handle-change % owner state)
                               })
               (dom/h2 nil "results")
               (om/build results-view app {:init-state state})
               (dom/h2 nil "queue")
               (om/build queue-view app {:init-state state})
               ))))

(defn init []
  (om/root player-view app-state
           {:target (. js/document (getElementById "om-app"))}))

(.addEventListener js/window "load" init)
