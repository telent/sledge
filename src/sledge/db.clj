(ns sledge.db
  (:import [java.util Date])
  (:require [clojure.java.io :as io]
            [clojure.pprint :refer [pp pprint]]
            [clojure.edn :as edn]
            [clojure.set :as set]
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
  (set/difference
   (set (remove str/blank? (str/split (.toLowerCase (or x "")) #"[ ,.()-]")))
   #{"the"}))

(def adjuncts
  {:artist {:tokenize-tags (comp #'lower-words :artist)
            :tokenize-query lower-words}
   :album {:tokenize-tags (comp #'lower-words :album)
           :tokenize-query lower-words}
   :title {:tokenize-tags (comp #'lower-words :title)
           :tokenize-query lower-words}
   :_content {:tokenize-tags (fn [r]
                               (lower-words
                                (str/join " "
                                          ((juxt :artist :album :title :genre)
                                           r))))
              :tokenize-query lower-words}
   ;; numeric field support is a bit rudimentary yet, it doesn't
   ;; do anything useful with "like".
   :year {:empty-map (sorted-map)
          :tokenize-tags #(if-let [y (:year %)]
                            (vector (Integer/parseInt y))
                            [])
          :tokenize-query #(vector (Integer/parseInt %))}
   })

;; for each entry [k v] in adjuncts, create map entry
;; from k to (make-adjunct-index name-map (:tokenize-tags v))
;; and have the end result attached to the primary index
;; somehow

(defn adjunctivize [name-map]
  (reduce
   (fn [m [attr tok]]
     (let [empty (get tok :empty-map {})]
       (assoc m attr (assoc-adjunct-index empty name-map (:tokenize-tags tok)))))
   {}
   adjuncts))

;; XXX async updates have to fit into this somehow eventually

(defn query-adjunct [index adjunct-name string]
  (let [kw (keyword adjunct-name)
        tokenizer (:tokenize-query (get adjuncts kw))
        tokens (tokenizer string)
        adjunct-map (get (:adjuncts index) kw)
        matches (map #(get adjunct-map %) tokens)]
    ;; return a collection of pathnames ordered by the number
    ;; of elements of matches that each appears within
    (let [relevance (fn [file]
                      (/ (count (filter #(contains? % file) matches))
                         (count tokens)))]
      (sort-by second >
               (map (fn [f] [f (relevance f)])
                    (apply set/union matches))))))

(defmulti where (fn [index [op & args]] op))

(defmethod where "like" [index [_ attr string]]
  (query-adjunct index attr string))

(defmethod where "=" [index [_ attr string]]
  (let [likes (query-adjunct index attr string)
        names @(:data index)]
    (filter (fn [[path rel]]
              (and (>= rel 1)
                   (let [tags (get names path)]
                     (= (get tags (keyword attr)) string))))
	    likes)))

(defn entries-where [index query]
  (let [data @(:data index)]
    (map #(if-let [r (get data (first %))]
            (assoc r :_score (second %)))
         (where index query))))

;; We have a bunch of [pathname relevance] for each constituent term
;; We want the pathnames that appear in all of the lists, with
;; relevance computed as the product of that pathname's relevance
;; in each constituent list

(defmethod where "and" [index [_ & terms]]
  (if terms
    (let [results (map #(into {} (where index %)) terms)
          paths (apply set/intersection (map #(set (keys %)) results))]
      (map (fn [path]
             [path (reduce * (map #(get % path) results))])
           paths))
    (map #(vector % 1) (keys @(:data index)))))

(defmethod where "or" [index [_ & terms]]
  (if terms
    (let [results (map #(into {} (where index %)) terms)
          paths (apply set/union (map #(set (keys %)) results))]
      (map (fn [path]
             [path (reduce max (map #(get % path 0) results))])
           paths))
    []))


(defn by-pathname [index pathname]
  (get @(:data index) pathname))

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
          {})
        adjunct-maps (adjunctivize name-map)
        ]
    (or (.isDirectory folder) (.mkdir folder))
    (or (.exists logfile) (.createNewFile logfile))
    {:folder folder :log logfile :data (atom name-map) :adjuncts adjunct-maps}
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
  (swap! (:data index) assoc k v)
  v)
