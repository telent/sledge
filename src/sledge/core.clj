(ns sledge.core
  (:require [clucy.core :as clucy]
            [clojure.string :as str]
            [sledge.server :as server]
            [sledge.search :as search]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [green-tags.core :as tags])
  (:import [org.jaudiotagger.audio.exceptions
            CannotReadException InvalidAudioFrameException])
  (:gen-class))

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


(defonce configuration (atom {}))

(defn read-config [file]
  (with-open [inf (java.io.PushbackReader. (io/reader file))]
    (edn/read inf)))

(defn -main [config-file & more-args]
  (reset! configuration (read-config config-file))
  (let [index-dir (io/file (:index @configuration))]
    (let [index (clucy/disk-index (.getPath index-dir))]
      (reset! search/lucene index)
      (when (not (.exists index-dir))
        (dorun (map (fn [d]
                      (println "creating index for " d)
                      (index-folder index d))
                    (:folders @configuration))))))
  (let [port (:port @configuration)]
    (server/start {:port port})
    (println "Sledge listening on port " port)))
