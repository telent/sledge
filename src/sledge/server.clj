(ns sledge.server
  (:require [sledge.search :as search]
            [clucy.core :as clucy]
            [hiccup.core :as h]
            [clojure.string :as str]
            [clojure.data.json :as json]
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

(defn tracks-json [req]
  (let [p (:params req)
        fields [:artist :album :title :year :album-artist :track :genre
                :length]
        num-rows 50
        terms (reduce (fn [terms k]
                        (if-let [v (get p (name k))]
                          (assoc terms k v)
                          terms))
                      {}
                      fields)
        project (if-let [f (get p "_fields" ) ]
                  #(select-keys % (map keyword (str/split f #",")))
                  identity)
        tracks (distinct (map project
                              (search/search search/index terms num-rows)))]
    {:status 200
     :headers {"Content-type" "text/json"}
     :body (json/write-str tracks)}))


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
              (clucy/search search/index q 10))])]]))

(defn artist-view [req]
  (let [tracks (search/search search/index {:artist (get (:params req) "artist")} 999)
        artist (str/join " / " (sort (distinct (map :artist tracks))))
        albums (sort (distinct (map :album tracks)))]
    nil))

(defn album-view [req]
  (let [artist (get (:params req) "artist")
        tracks (clucy/search
                search/index
                (str "album: " (pr-str (get (:params req) "album"))
                     " AND (artist: " artist " OR album-artist: " artist ")")
                999)
        artists (distinct (map :artist tracks))
        name (distinct (map :album tracks))]
    nil))

(defn ringo [view]
  (fn [req]
    {:status 200
     :headers {"Content-type" "text/html; charset=UTF-8"}
     :body (h/html (view req))}))


;; avconv -i /srv/media/Music/flac/Delerium-Karma\ Disc\ 1/04.Silence.flac -f mp3 pipe: |cat > s.mp3


(defn routes [req]
  (let [u (:uri req)]
    (cond
     (.startsWith u "/tracks.json") (tracks-json req)
     :else ((ringo front-page-view) req)
     )))

(def app (res/wrap-resource (wp/wrap-params #'routes) "/"))

(defonce server (atom nil))

(defn stop-server []
  (when-not (nil? @server)
    (@server :timeout 100)
    (reset! server nil)))

(defn start []
  (stop-server)
  (reset! server (http/run-server #'app {:port 53281})))
