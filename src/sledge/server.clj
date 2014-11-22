(ns sledge.server
  (:require [sledge.search :as search]
            [clucy.core :as clucy]
            [hiccup.core :as h]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [org.httpkit.server :as http]
            [ring.middleware.params :as wp]
            [ring.middleware.resource :as res]
            )
  (:import [org.apache.commons.codec.binary Base64 Hex]))


(defn base64 [string]
  (Base64/encodeBase64URLSafeString (.getBytes string)))

(defn unbase64 [string]
  ;; apache commons decodeBase64 appears to not cope very well if it doesn't
  ;; get the padding it expects, so we have to add that back in
  (let [l (count string)
        pad-l (- 4 (- l (* 4 (quot l 4))))]
    (String. (Base64/decodeBase64 (str string (str/join (repeat pad-l "=")))))))

(assert
 (= (base64
     "this string will base64 encode into something longher than one line")
    "dGhpcyBzdHJpbmcgd2lsbCBiYXNlNjQgZW5jb2RlIGludG8gc29tZXRoaW5nIGxvbmdoZXIgdGhhbiBvbmUgbGluZQ"))

(assert
 (= (unbase64
     "dGhpcyBzdHJpbmcgd2lsbCBiYXNlNjQgZW5jb2RlIGludG8gc29tZXRoaW5nIGxvbmdoZXIgdGhhbiBvbmUgbGluZQ")
    "this string will base64 encode into something longher than one line"))

;; given some input format, decide what format(s) we can offer it
;; to the user in

;; the keys to this map correspond to getEncodingType as returns by
;; JAudioTagger

(def encoding-types
  {"mp3" {:mime "audio/mpeg" :suffix "mp3" :transcode []}
   "FLAC 16 bits" {:mime "audio/flac" :suffix "flac" :transcode ["mp3"]}
   "ASF (audio): 0x0161 (Windows Media Audio (ver 7,8,9))"
   {:mime "audio/x-ms-asf" :suffix "asf" :transcode []}
   })

(defn media-links [r]
  (let [e-t (:encoding-type r)
        enc (or (get encoding-types e-t)
                (throw (Exception. (str "unsupported encoding-type " e-t))))
        basename (base64 (:pathname r))]
    (reduce (fn [h fmt]
              (assoc h fmt { "href" (str "/bits/" basename "."  fmt)}))
            {}
            (conj (seq (:transcode enc)) (:suffix enc)))))

(assert
 (=
  (media-links {:encoding-type "FLAC 16 bits"
                :pathname "/path/to/audio.flac"})
  {"mp3" {"href" "/bits/L3BhdGgvdG8vYXVkaW8uZmxhYw.mp3"}, "flac" {"href" "/bits/L3BhdGgvdG8vYXVkaW8uZmxhYw.flac"}}))

(defn tracks-json [req]
  (let [p (:params req)
        fields [:artist :album :title :year :album-artist :track :genre
                :encoding-type
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
                  #(assoc % "_links" (media-links %)))
        tracks (distinct (map project
                              (search/search search/index terms num-rows)))]
    {:status 200
     :headers {"Content-type" "text/json"}
     :body (json/write-str tracks)}))


(def scripts
  {:dev ["http://fb.me/react-0.11.1.js",
         "out/goog/base.js"
         "out/main.js"]
   :production ["production-out/main.js"]
   })


(defn front-page-view [req]
  [:html
   [:head
    [:title "Sledge"]
    [:link {:rel "stylesheet"
            :type "text/css"
            :href "/resources/css/sledge.css"
            }]]
   [:body
    [:div {:id "om-app"}]
    (map (fn [url] [:script {:src url :type "text/javascript"}])
         (:dev scripts))
    [:script "goog.require(\"sledge.core\");"]
    [:p "Lost in music"]]])


(defn ringo [view]
  (fn [req]
    {:status 200
     :headers {"Content-type" "text/html; charset=UTF-8"}
     :body (h/html (view req))}))



(def mime-types
  {"ogg" "audio/ogg"
   "mp3" "audio/mpeg"
   "flac" "audio/flac"
   "wav" "audio/x-wav"
   })

(defn mime-type-for-ext [ext]
  (get mime-types (.toLowerCase ext)))

;; avconv -i /srv/media/Music/flac/Delerium-Karma\ Disc\ 1/04.Silence.flac -f mp3 pipe: |cat > s.mp3

(defn maybe-transcode [pathname from to]
  (let [from (:suffix (get encoding-types from))
        mime-type (mime-type-for-ext to)]
    (if (= from to)
      {:status 200 :headers {"content-type" mime-type}
       :body (clojure.java.io/file pathname)}
      {:status 404 :headers {"content-type" "text/plain"}
       :body "transcoding not implemented"})))

(defn bits-handler [req]
  (let [urlpath (str/split (:uri req) #"/")
        [b64 ext] (str/split (get urlpath 2) #"\.")
        real-pathname (unbase64 b64)]
    (println real-pathname)
    ;; XXX this *really* needs to be an exact match
    (if-let [r (first (filter
                       #(= (:pathname %) real-pathname)
                       (search/search search/index {:pathname real-pathname} 100)))]
      (maybe-transcode real-pathname (:encoding-type r) ext)
      {:status 404 :body "not found"})))

(defn routes [req]
  (let [u (:uri req)]
    (cond
     (.startsWith u "/tracks.json") (tracks-json req)
     (.startsWith u "/bits/") (bits-handler req)
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
