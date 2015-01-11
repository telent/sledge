(ns sledge.search
  (:import [java.util Date])
  (:require [clue.core :as clue]
            [clojure.string :as str])  )

(defn open-index [filename]
  (let [i (clue/index :path (.getPath filename))]
    (if-not (.exists filename)
      (clue/add i {:created (java.util.Date.)}))
    i))

(defn stringize-search-map [m]
  (str/join " AND " (map (fn [[k v]] (str (name k) ":" (pr-str v))) m)))

(defonce lucene (atom nil))

(defn search [index map num]
  (take num (clue/search index (stringize-search-map map) )))

(defn query [index string num]
  (take num (clue/search index string )))
