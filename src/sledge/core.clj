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
    (let [index (clucy/disk-index (.getPath index-dir))]
      (reset! search/lucene index)
      (when (not (.exists index-dir))
        (dorun (map (fn [d]
                      (println "creating index for " d)
                      (scan/index-folder index d))
                    (:folders @configuration))))))
  (let [port (:port @configuration)]
    (server/start {:port port})
    (println "Sledge listening on port " port)))
