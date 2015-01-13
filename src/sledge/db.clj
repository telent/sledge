(ns sledge.db
  (:import [java.util Date])
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str])  )


;; adjunct indexes map from tokenized attribute values
;; (["orbitall"], ["little","fluffy", "clouds"] or whatever)
;; to pathnames.

(defn add-tokens [token-map val tokens]
  (merge-with clojure.set/union
      	      token-map
              (into {} (map vector tokens (repeat #{val})))))

(assert (= (add-tokens {} "/hell.mp3" ["Foo" "Fighters"])
           {"Fighters" #{"/hell.mp3"}, "Foo" #{"/hell.mp3"}}))

(defn assoc-adjunct-index [index name-map tokenize]
  (reduce (fn [m [filename tags]]
            (add-tokens m filename (tokenize tags)))
          index
          name-map))

(defn make-adjunct-index [name-map tokenize]
  (assoc-adjunct-index {} name-map tokenize))

(let [name-map {"/l.mp3" {:author "Foo Fighters"}
                "/pretenders.mp3" {:author "Foo Fighters"}
                "/box.mp3" {:author "Orbital" :title "The Box"}}]
  (make-adjunct-index name-map
                      #(set (str/split (:author %) #" "))))


(defn lower-words [x]
  (set (remove str/blank? (str/split (.toLowerCase x) #"[ ,.]"))))

(def adjuncts
  {:author {:tokenize-tags (comp lower-words :author)
            :tokenize-query lower-words}
   :title {:tokenize-tags (comp lower-words :title)
           :tokenize-query lower-words}
   })

;; for each entry [k v] in adjuncts, create map entry
;; from k to (make-adjunct-index name-map (:tokenize-tags v))
;; and have the end result attached to the primary index
;; somehow

;; XXX async updates have to fit into this somehow eventually

;;;
;; the main index maps from pathname to hash of tags

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
    {:folder folder :log logfile :data (atom name-map)}
    ))

(defonce the-index (atom nil))



(defn write-log [name-map filename]
  (with-open [f (io/writer filename)]
    (binding [*out* f]
      (dorun (map prn name-map)))))

(defn save-index [index]
  (let [tmpname (io/file (:folder index) "tmplog.edn")]
    (write-log @(:data index) tmpname)
    (.renameTo tmpname (:log index))
    index))

(defn save-entry [index k v]
  (swap! (:data index) assoc k (assoc v "_content" (str/join " " (vals v))))
  v)
