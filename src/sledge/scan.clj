(ns sledge.scan
  (:require [sledge.db :as db]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.core.async :as async :refer [>!! >! <! go chan]]
            [juxt.dirwatch :refer (watch-dir)]
            [green-tags.core :as tags])
  (:import [org.jaudiotagger.audio.exceptions
            CannotReadException InvalidAudioFrameException]))

(defn tags [f]
  (assoc
      (try
        (tags/get-all-info f)
        (catch InvalidAudioFrameException e {:exception e})
        (catch CannotReadException e {:exception e}))
    :pathname (.getPath f)))

(defn file-ext [file]
  (last (str/split (.getName (io/file file)) #"\.")))

(defn music-file? [file]
  (and (.isFile file)
       (contains? #{"mp3" "flac" "aac" "ogg" "wma"}
                  (.toLowerCase (file-ext file)))))

(defn changed-since? [time file]
  (> (.lastModified file) time))

(defn music-files [path]
  (filter music-file? (file-seq (clojure.java.io/file path))))

(defn watcher-chan [folders]
  (let [ch (chan)
        ;; watch-dir invokes its callback in an agent, so won't block
        ;; the main thread if there's nothing listening to the channel
        send-events (fn [notif] (>!! ch notif))]
    (dorun (map #(watch-dir send-events (io/file %)) folders))
    ch))

(defmulti update-for-file (fn [index o] (:action o)))

(defmethod update-for-file :create [index-ref o]
  (let [f (:file o)]
    (when (music-file? f)
      (println [:scanning f])
      (swap! index-ref db/update-entry (.getPath f) (tags f)))))

(defmethod update-for-file :modify [index-ref o]
  (let [f (:file o)]
    (when (music-file? f)
      (println [:scanning f])
      (swap! index-ref db/update-entry (.getPath f) (tags f)))))

(defmethod update-for-file :delete [index-ref o]
  (println [:deleting (:file o)])
  #_(clucy/search-and-delete index (str "pathname:" (.getPath (:file o)))))


(defn watch-folders [index-ref last-run-time folders]
  (let [recent (map (fn [folder]
                      (filter (partial changed-since? last-run-time)
                              (music-files folder)))
                    folders)
        updates (watcher-chan folders)]
    (go
     (async/onto-chan updates
                      (map #(assoc {} :file % :action :modify) (flatten recent))
                      false)
     (while true
       (update-for-file index-ref (<! updates))))))
