(ns sledge.server
  (:require [sledge.core :as core]
            [clucy.core :as clucy]
            [hiccup.core :as h]
            [clojure.string :as str]
            [org.httpkit.server :as http]
            [ring.middleware.params :as wp]
            [ring.middleware.resource :as res]
            ))

(defn head [title]
  [:head
   [:title (str/join " - " (filter identity [title "Sledge"]))]
   [:link {:rel "stylesheet"
           :type "text/css"
           :href "/resources/css/sledge.css"
           }]])

(defn search-form [q]
  [:form {:action "/"}
   "Search " [:input {
                      :type :text :size 60 :name "q" :value (or q "")}]
   [:input {:type :submit :value "Go"}]])

(defn artist-link [artist]
  [:a {:href (str "/artist?artist=" (pr-str artist))} artist])

(defn album-link [artist album]
  [:a {:href (str "/album?artist=" (pr-str artist)
                  "&album=" (pr-str album))}
   album])


(defn format-result [r]
  (let [artist (:artist r)
        album (:album r)
        title (:title r)
        artist-l (artist-link artist)
        album-l  (album-link (or (:album-artist r) (:artist r)) album)]
    [:span {} artist-l " / " album-l " / " title]))

(defn front-page-view [req]
  (let [q (get (:params req) "q" nil)]
    [:html
     (head nil)
     [:body
      [:h1 "Sledge"]
      [:p "Lost in music"]
      (search-form q)
      (when q
        [:ul
         (map (fn [r] [:li (format-result r)])
              (clucy/search core/index q 10))])]]))

(defn artist-view [req]
  (let [tracks (core/search core/index {:artist (get (:params req) "artist")} 999)
        artist (str/join " / " (sort (distinct (map :artist tracks))))
        albums (sort (distinct (map :album tracks)))]
    [:html
     (head nil)
     [:body
      [:h1 artist]
      (search-form (str "artist: " (pr-str artist)))
      [:ul
       (map (fn [album] [:li (album-link artist album)])
            albums)]]]))

(defn album-view [req]
  (let [artist (get (:params req) "artist")
        tracks (clucy/search
                core/index
                (str "album: " (pr-str (get (:params req) "album"))
                     " AND (artist: " artist " OR album-artist: " artist ")")
                999)
        artists (distinct (map :artist tracks))
        name (distinct (map :album tracks))]
    [:html
     (head nil)
     [:body
      [:h1 (str/join " / " name)]
      [:h2 (str/join " / " artists)]
      (search-form nil)
      [:ul
       (map (fn [r] [:li (format-result r)])
            (sort-by :track-num tracks))]]]))

(defn ringo [view]
  (fn [req]
    {:status 200
     :headers {"Content-type" "text/html; charset=UTF-8"}
     :body (h/html (view req))}))

(defn routes [req]
  (let [u (:uri req)]
    (cond
     (.startsWith u "/album") ((ringo album-view) req)
     (.startsWith u "/artist") ((ringo artist-view) req)
     :else ((ringo front-page-view) req)
     )))

(def app (res/wrap-resource (wp/wrap-params #'routes)))

(defonce server (atom nil))

(defn stop-server []
  (when-not (nil? @server)
    (@server :timeout 100)
    (reset! server nil)))

(defn start []
  (stop-server)
  (reset! server (http/run-server #'app {:port 53281})))
