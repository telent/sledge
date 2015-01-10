(ns sledge.core
  (:require [clucy.core :as clucy]
            [clojure.string :as str]
            [sledge.server :as server]
            [sledge.search :as search]
            [sledge.scan :as scan]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [green-tags.core :as tags])
  (:import [org.jaudiotagger.audio.exceptions
            CannotReadException InvalidAudioFrameException])
  (:gen-class))


(defonce configuration (atom {}))

(defn read-config [file]
  (with-open [inf (java.io.PushbackReader. (io/reader file))]
    (edn/read inf)))

(defn -main [config-file & more-args]
  (reset! configuration (read-config config-file))
  (let [index-dir (io/file (:index @configuration))]
    (let [index (clucy/disk-index (.getPath index-dir))
          folders (:folders @configuration)
          last-index-time
          (if (.exists index-dir)  (.lastModified index-dir) 0)
          ]
      (when-not (.exists index-dir)
        (clucy/add index {:version 1 :created  (java.util.Date.)}))
      (reset! search/lucene index)
      (scan/watch-folders index last-index-time folders)))
  (let [port (:port @configuration)]
    (server/start {:port port})
    (println "Sledge listening on port " port)))
