(ns sledge.server
  (:require [sledge.db :as db]
            [sledge.transcode :as transcode]
            [clojure.core.async :as async]
            [hiccup.core :as h]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [aleph.http :as http]
            [manifold.stream :as manifold]
            [ring.middleware.params :as wp]
            [ring.middleware.resource :as res]
            )
  (:import [org.apache.commons.codec.binary Base64 Hex]))

(defonce enable-brepl (System/getProperty "enable_brepl"))
(when enable-brepl
  (require '[simple-brepl.service ])
  (println "browser repl enabled"))

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

(media-links {:encoding-type "FLAC 16 bits"
              :pathname "/path/to/audio.flac"})


(assert
 (=
  (media-links {:encoding-type "FLAC 16 bits"
                :pathname "/path/to/audio.flac"})
  {"mp3"
   {"href" "/bits/L3BhdGgvdG8vYXVkaW8uZmxhYw.mp3", "codecs" "mp3", "type" "audio/mpeg"},
   "ogg"
   {"href" "/bits/L3BhdGgvdG8vYXVkaW8uZmxhYw.ogg", "codecs" "vorbis", "type" "audio/ogg"}
   "flac"
   {"href" "/bits/L3BhdGgvdG8vYXVkaW8uZmxhYw.flac", "codecs" nil, "type" "audio/flac"}}))

;  :; curl -v -XPOST -H'content-type: text/plain' --data-binary '["like","_content","rhye"]' http://localhost:53281/tracks.json


(defn tracks-data [req]
  (let [body (if-let [b (:body req)] (slurp b) "[]")
        query (json/read-str body)
        num-rows 50
        project (if-let [f nil #_ (get p "_fields" ) ]
                  #(select-keys % (map keyword (str/split f #",")))
                  #(assoc %
                     "_links" (media-links %)))]
    (distinct (map project
                   (take num-rows (db/entries-where (:db req) query ))))))

(defn tracks-json-handler [req]
  {:status 200
   :headers {"Content-type" "text/json"}
   :body (json/write-str (tracks-data req))})

(def scripts
  {:dev ["out/main.js"]
   :production ["production-out/main.js"]
   })

(defn front-page-view [req]
  [:html
   [:head
    [:title "Sledge"]
    [:meta {:name "viewport" :content "initial-scale=1.0"}]
    [:script (if enable-brepl
               ((ns-resolve 'simple-brepl.service 'brepl-js))
               "/* no brepl */")]
    [:link {:rel "stylesheet"
            :type "text/css"
            :href "/css/sledge.css"
            }]]
   [:body
    [:div {:id "om-app"}]
    (map (fn [url] [:script {:src url :type "text/javascript"}])
         (get scripts (if enable-brepl :dev :production)))
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
     :else ((ringo front-page-view) req)
     )))


(def app (res/wrap-resource (wp/wrap-params #'routes) "/"))

(defn wrap-db [app db]
  (fn [request]
    (app (assoc request :db @db))))

(defonce server (atom nil))

(defn start [db-ref options]
  (let [opts (merge {:port 53281} options)]
    (println [:opts opts])
    (reset! server (http/start-server (wrap-db #'app db-ref) opts))))
