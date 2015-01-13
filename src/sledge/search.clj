(ns sledge.search
  (:import [java.util Date])
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str])  )

(defn open-index [name]
  (let [folder (io/file name)
        logfile (io/file folder "log.edn")
        name-map
        (if (.exists logfile)
          (with-open [f (java.io.PushbackReader. (clojure.java.io/reader logfile))]
            (let [forms (repeatedly (partial edn/read {:eof nil} f))]
              (reduce (fn [m [k v]] (assoc m k v))
                      {}
                      (take-while identity forms))))
          {})]
    (or (.isDirectory folder) (.mkdir folder))
    (or (.exists logfile) (.createNewFile logfile))
    {:folder folder :log logfile :data name-map}))

(defonce the-index (atom nil))
