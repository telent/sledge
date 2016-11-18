(ns sledge.server
  (:require [sledge.db :as db]
            [sledge.transcode :as transcode]
            [clojure.core.async :as async]
            [hiccup.core :as h]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [aleph.http :as http]
            [manifold.stream :as manifold]
            [ring.middleware.params :as wp]
            [ring.middleware.resource :as res]
            [ring.util.response :as response]
            [clojure.test :as test :refer [deftest is testing]]
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

(deftest all-your-base64
  (let [from "this string will base64 encode into something longher than one line"
        to "dGhpcyBzdHJpbmcgd2lsbCBiYXNlNjQgZW5jb2RlIGludG8gc29tZXRoaW5nIGxvbmdoZXIgdGhhbiBvbmUgbGluZQ"]
    (is (= (base64 from) to))
    (is (= (unbase64 to) from))))

;; given some input format, decide what format(s) we can offer it
;; to the user in

;; the keys to this map correspond to getEncodingType as returns by
;; JAudioTagger

(def encoding-types
  {"mp3" {:suffix "mp3" :transcode #{"ogg"}}
   "Ogg Vorbis v1" {:suffix "ogg" :transcode #{}}
   "FLAC 16 bits" {:suffix "flac" :transcode #{"mp3" "ogg"}}
   "ASF (audio): 0x0161 (Windows Media Audio (ver 7,8,9))"
   {:suffix "asf" :transcode #{}}})

(defn can-encode-as? [from to]
  (some #(and (= (:suffix %) from) (contains? (:transcode %) to))
        (vals encoding-types)))

(def mime-types
  {"ogg" "audio/ogg"
   "mp3" "audio/mpeg"
   "flac" "audio/flac"
   "wav" "audio/x-wav"
   "asf" "audio/x-ms-asf"
   })

(defn mime-type-for-ext [ext]
  (get mime-types (.toLowerCase ext)))

(defn codec-for-ext [ext]
  (case (.toLowerCase ext)
    "mp3" "mp3"
    "ogg" "vorbis"
    "wav" "1"
    nil))

(defn media-links [r]
  (let [e-t (:encoding-type r)
        enc (or (get encoding-types e-t)
                (throw (Exception. (str "unsupported encoding-type "
                                        e-t
                                        " for "
                                        (:pathname r)
                                        ))))
        basename (base64 (:pathname r))]
    (reduce (fn [h fmt]
              (assoc h fmt { "href" (str "/bits/" basename "."  fmt)
                             "codecs" (codec-for-ext fmt)
                             "type" (mime-type-for-ext fmt)}))
            {}
            (conj (seq (:transcode enc)) (:suffix enc)))))

(deftest media-links-test
  (is (=
       (media-links {:encoding-type "FLAC 16 bits"
                     :pathname "/path/to/audio.flac"})
       {"mp3"
        {"href" "/bits/L3BhdGgvdG8vYXVkaW8uZmxhYw.mp3", "codecs" "mp3", "type" "audio/mpeg"},
        "ogg"
        {"href" "/bits/L3BhdGgvdG8vYXVkaW8uZmxhYw.ogg", "codecs" "vorbis", "type" "audio/ogg"}
        "flac"
        {"href" "/bits/L3BhdGgvdG8vYXVkaW8uZmxhYw.flac", "codecs" nil, "type" "audio/flac"}})))

;; :; curl -v -XPOST -H'content-type: text/plain' --data-binary '["like","_content","rhye"]' http://localhost:53281/tracks.json


(defn tracks-for-query
  ([db query] (tracks-for-query db query 50))
  ([db query num-rows]
   (let [project (if-let [f nil #_ (get p "_fields" ) ]
                   #(select-keys % (map keyword (str/split f #",")))
                   #(assoc %
                           "_links" (media-links %)))]
     (distinct (map project
                    (take num-rows (db/entries-where db query )))))))

(defn tracks-data [req]
  (let [body (if-let [b (:body req)] (slurp b) "[]")]
    (tracks-for-query (:db req) (json/read-str body) 50)))

(defn tracks-json-handler [req]
  {:status 200
   :headers {"Content-type" "text/json"}
   :body (json/write-str (tracks-data req))})

(defn front-page-view [req]
  [:html
   [:head
    [:title "Sledge"]
    [:meta {:name "viewport" :content "initial-scale=1.0"}]
    [:link {:rel "stylesheet"
            :type "text/css"
            :href "/assets/css/sledge.css"
            }]
        [:link {:rel "stylesheet"
            :type "text/css"
            :href "/assets/css/palette.css"
            }]]
   [:body
    [:div {:id "om-app"}]
    (map (fn [url] [:script {:src url :type "text/javascript"}])
         ["/assets/js/main.js"])
    ]])

(defn ringo [view]
  (fn [req]
    {:status 200
     :headers {"Content-type" "text/html; charset=UTF-8"}
     :body (h/html (view req))}))


(defn transcode-chan [pathname format]
  (let [transcode-stream (transcode/avconv pathname format)
        chan (async/chan)
        thr (future
              (let [buf (byte-array 4096)]
                (loop []
                  (let [w (.read transcode-stream buf)]
                    (cond (> w 0)
                          (do
                            (async/>!!
                             chan
                             (java.util.Arrays/copyOf buf w))
                            (recur))
                          (< w 0)
                          (async/close! chan))))))]
    chan))

(defn transcode-handler [request pathname to-format]
  (let [mime-type (mime-type-for-ext to-format)
        content-type (if-let [c (codec-for-ext to-format)]
                       (str mime-type "; codecs=" c)
                       mime-type)]
    {:status 200
     :headers {"content-type" content-type}
     :body (manifold/->source (transcode-chan pathname to-format))}))

(defn maybe-transcode [req pathname from to]
  (let [from (:suffix (get encoding-types from))
        mime-type (mime-type-for-ext to)]
    (cond (= from to)
          {:status 200 :headers {"content-type" mime-type}
           :body (clojure.java.io/file pathname)}
          (can-encode-as? from to)
          (transcode-handler req pathname to)
          :else
          {:status 404 :headers {"content-type" "text/plain"}
           :body "transcoding not implemented for requested format"})))

(defn bits-handler [req]
  (let [urlpath (str/split (:uri req) #"/")
        [b64 ext] (str/split (get urlpath 2) #"\.")
        real-pathname (unbase64 b64)]
    (if-let [r (db/by-pathname (:db req) real-pathname)]
      (maybe-transcode req real-pathname (:encoding-type r) ext)
      {:status 404 :body "not found"})))


(defn routes [req]
  (let [u (:uri req)]
    (cond
     (.startsWith u "/tracks.json") (tracks-json-handler req)
     (.startsWith u "/bits/") (bits-handler req)
     (= u "/") ((ringo front-page-view) req)
     :else {:status 404 :headers {} :body "not found"}
     )))

(defn wrap-target-dir [app dir]
  (fn [request]
    (let [n (subs (:uri request) 1)
          f (io/file dir n)]
      (if (.isFile f)
        (response/file-response (.getPath f))
        (app request)))))

(defn wrap-db [app db]
  (fn [request]
    (app (assoc request :db @db))))

(def app
  (-> routes
      wp/wrap-params
      (res/wrap-resource "/")
      (wrap-target-dir "dev-target")))


(defonce server (atom nil))

(defn start [db-ref options]
  (let [opts (merge {:port 53281} options)]
    (println [:opts opts])
    (when @server
      (.close @server))
    (reset! server (http/start-server
                    (wrap-db #'app db-ref)
                    opts))))
