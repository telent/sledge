(ns sledge.server
  (:require [sledge.search :as search]
            [sledge.transcode :as transcode]
            [clucy.core :as clucy]
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
  {"mp3" {:mime "audio/mpeg" :suffix "mp3" :transcode ["ogg"]}
   "FLAC 16 bits" {:mime "audio/flac" :suffix "flac" :transcode ["mp3" "ogg"]}
   "ASF (audio): 0x0161 (Windows Media Audio (ver 7,8,9))"
   {:mime "audio/x-ms-asf" :suffix "asf" :transcode []}
   })

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
              (assoc h fmt { "href" (str "/bits/" basename "."  fmt)}))
            {}
            (conj (seq (:transcode enc)) (:suffix enc)))))

(assert
 (=
  (media-links {:encoding-type "FLAC 16 bits"
                :pathname "/path/to/audio.flac"})
  {"mp3" {"href" "/bits/L3BhdGgvdG8vYXVkaW8uZmxhYw.mp3"},
   "ogg" {"href" "/bits/L3BhdGgvdG8vYXVkaW8uZmxhYw.ogg"},
   "flac" {"href" "/bits/L3BhdGgvdG8vYXVkaW8uZmxhYw.flac"}}))

(defn query-for-request-params [params]
  (let [fields [:artist :album :title :year :album-artist :track :genre
                :encoding-type
                :length]
        terms (reduce (fn [terms k]
                        (if-let [v (get params (name k))]
                          (assoc terms k v)
                          terms))
                      {}
                      fields)]
    (and (first terms) (search/stringize-search-map terms))))

;;  curl -v -XPOST -H'content-type: text/plain' --data-binary 'rhye' http://localhost:53281/tracks.json

(defn tracks-data [req]
  (let [p (:params req)
        filters (query-for-request-params p)
        query (str "("
                   (or (and (= (:request-method req) :post)
                            (:body req)
                            (slurp (:body req)))
                       "TRUE")
                   ") AND ("
                   (or filters " TRUE ")
                   ")")
        num-rows 50
        project (if-let [f (get p "_fields" ) ]
                  #(select-keys % (map keyword (str/split f #",")))
                  #(assoc %
                     "_score" (:_score (meta %))
                     "_links" (media-links %)))]
    (distinct (map project
                   (clucy/search @search/lucene query num-rows)))))

(defn tracks-json-handler [req]
  {:status 200
   :headers {"Content-type" "text/json"}
   :body (json/write-str (tracks-data req))})


(def scripts
  {:dev ["/react/react.js"
         "out/goog/base.js"
         "out/main.js"]
   :production ["production-out/main.js"]
   })


(defn front-page-view [req]
  [:html
   [:head
    [:title "Sledge"]
    [:meta {:name "viewport" :content "initial-scale=1.0"}]
    [:link {:rel "stylesheet"
            :type "text/css"
            :href "/css/sledge.css"
            }]]
   [:body
    [:div {:id "om-app"}]
    (map (fn [url] [:script {:src url :type "text/javascript"}])
         (:dev scripts))
    [:script "goog.require(\"sledge.core\");"]
    [:footer {}
     ;; maybe this can go into a popup or 'about'
     ;; item somewhere
     [:p "Copyright &copy;2014 Daniel Barlow"]
     [:p
      [:a {:href "http://www.gnu.org/licenses/agpl.html"}
       "GNU Affero General Public Licence"]
      " | "
      [:a {:href "http://github.com/telent/sledge"}
       "Download"]]]
    ]])


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

(defn transcode-chan [pathname]
  (let [transcode-stream (transcode/to-ogg pathname)
        chan (async/chan)
        thr (future
              (let [buf (byte-array 4096)]
                (with-open [writer (java.io.FileOutputStream. "/tmp/y.ogg")]
                  (loop []
                    (let [w (.read transcode-stream buf)]
                      (cond (> w 0)
                            (do
                              (.write writer buf 0 w)
                              (async/>!!
                               chan
                               (java.util.Arrays/copyOf buf w))
                              (recur))
                            (< w 0)
                            (async/close! chan)))))))]
    chan))

(defn transcode-handler [request pathname]
  {:status 200
   :headers {"content-type" "audio/ogg"}
   :body (manifold/->source (transcode-chan pathname))})

(defn maybe-transcode [req pathname from to]
  (let [from (:suffix (get encoding-types from))
        mime-type (mime-type-for-ext to)]
    (cond (= from to)
          {:status 200 :headers {"content-type" mime-type}
           :body (clojure.java.io/file pathname)}
          (= to "ogg")
          (transcode-handler req pathname)
          :else
          {:status 404 :headers {"content-type" "text/plain"}
           :body "transcoding not implemented"})))

(defn bits-handler [req]
  (let [urlpath (str/split (:uri req) #"/")
        [b64 ext] (str/split (get urlpath 2) #"\.")
        real-pathname (unbase64 b64)]
    ;; XXX this *really* needs to be an exact match
    (if-let [r (first (filter
                       #(= (:pathname %) real-pathname)
                       (search/search @search/lucene
                                      {:pathname real-pathname} 100)))]
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

(defonce server (atom nil))

(defn start [options]
  (let [opts (merge {:port 53281} options)]
    (println [:opts opts])
    (reset! server (http/start-server #'app opts))))
