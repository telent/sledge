(ns sledge.scan
  (:require [clucy.core :as clucy]
            [sledge.search :as search]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [green-tags.core :as tags])
  (:import [org.jaudiotagger.audio.exceptions
            CannotReadException InvalidAudioFrameException]))

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

(defn store-tags [index tags]
  (clucy/add index tags)
  index)

(defn upsert-tags [index tags]
  (if (first (search/search index {:pathname (:pathname tags)} 1))
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
