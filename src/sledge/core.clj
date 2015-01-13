(ns sledge.core
  (:require [clojure.string :as str]
            #_[sledge.server :as server]
            [sledge.db :as db]
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
    (let [last-index-time
          (if (.exists index-dir)  (.lastModified index-dir) 0)
          index (db/open-index index-dir)
          folders (:folders @configuration)]
      (reset! db/the-index index)
      (scan/watch-folders index last-index-time folders)))
  #_(let [port (:port @configuration)]
    (server/start {:port port})
    (println "Sledge listening on port " port)))
