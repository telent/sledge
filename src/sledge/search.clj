(ns sledge.search
  (:require [clucy.core :as clucy]
            [clojure.string :as str])  )

(defn stringize-search-map [m]
  (str/join " AND " (map (fn [[k v]] (str (name k) ":" (pr-str v))) m)))

(defonce lucene (atom nil))

(defn search [index map num]
  (clucy/search index (stringize-search-map map) num))
