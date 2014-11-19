(ns sledge.core
  (:require [clucy.core :as clucy]
            [clojure.string :as str]
            [green-tags.core :as tags])
  (:import [org.jaudiotagger.audio.exceptions
            CannotReadException InvalidAudioFrameException])
  (:gen-class))

(def index (clucy/disk-index "/tmp/sledge1/"))

(defn tags [f]
  (assoc
      (try
        (tags/get-all-info f)
        (catch InvalidAudioFrameException e {:exception e})
        (catch CannotReadException e {:exception e}))
    :pathname (.getPath f)))

(defn file-ext [file]
  (last (str/split (.getName (clojure.java.io/file file)) #"\.")))

(defn music-file? [file]
  (and (.isFile file)
       (contains? #{"mp3" "flac" "aac" "ogg" "wma"}
                  (.toLowerCase (file-ext file)))))

(defn music-files [path]
  (filter music-file? (file-seq (clojure.java.io/file path))))

(defn stringize-search-map [m]
  (str/join " " (map (fn [[k v]] (str (name k) ":" (pr-str v))) m)))

(defn search [index map num]
  (clucy/search index (stringize-search-map map) num))

(defn store-tags [index tags]
  (clucy/add index tags)
  index)

(defn upsert-tags [index tags]
  (if (first (search index {:pathname (:pathname tags)} 1))
    index
    (store-tags index tags)))

;; 26 seconds to index 1000 files
;; 4.4s to upsert

(defn index-folder [index folder]
  (reduce store-tags index
          (map tags (music-files folder))))

(defn freshen-folder [index folder]
  (reduce upsert-tags index
          (map tags (music-files folder))))

;; we need a web server with
;; 1) a front page with a search box & other examples
;; 2) a page for each album
;; 3) a page for each artist
;; 4) a page for each track
;; 5) each page with links to audio has an xspf link
;; 6) a handler to transcode and download the actual audio

;; avconv -i /srv/media/Music/flac/Delerium-Karma\ Disc\ 1/04.Silence.flac -f mp3 pipe: |cat > s.mp3


(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (println "Hello, World!"))
