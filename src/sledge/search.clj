(ns sledge.search
  (:require [clucy.core :as clucy]
            [clojure.string :as str])  )

(def index (clucy/disk-index "/tmp/sledge1/"))

(defn stringize-search-map [m]
  (str/join " " (map (fn [[k v]] (str (name k) ":" (pr-str v))) m)))

(defn search [index map num]
  (clucy/search index (stringize-search-map map) num))
