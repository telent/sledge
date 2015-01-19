(ns sledge.core
  (:require [clojure.string :as str]
            [sledge.server :as server]
            [sledge.db :as db]
            [sledge.scan :as scan]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [green-tags.core :as tags])
  (:import [org.jaudiotagger.audio.exceptions
            CannotReadException InvalidAudioFrameException])
  (:gen-class))


(defonce configuration (atom {}))

;; this is only used for repl and testing
(defonce the-database (atom nil))

(defn read-config [file]
  (with-open [inf (java.io.PushbackReader. (io/reader file))]
    (edn/read inf)))

(defn -main [config-file & more-args]
  (reset! configuration (read-config config-file))
  (let [index (db/open-index (io/file (:index @configuration)))
        folders (:folders @configuration)]
    (reset! the-database index)
    (scan/watch-folders the-database (db/last-modified index) folders)
    (let [port (:port @configuration)]
      (server/start index {:port port})
      (println "Sledge listening on port " port))))
