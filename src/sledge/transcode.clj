(ns sledge.transcode
  (:import [java.lang Runtime Process]))

;;  avconv -i /srv/media/Music/flac/Delerium-Karma\ Disc\ 1/04.Silence.flac -f ogg -c libvorbis pipe: |cat >s.ogg


(defn to-ogg [filename]
  (let [rt (Runtime/getRuntime)
        p (.exec rt
                 (into-array
                  ["/usr/bin/avconv",
                   "-i",
                   filename,
                   "-f"
                   "ogg",
                   "-c"
                   "libvorbis"
                   "pipe:"])
                 ;(into-array [])                     ;env
                 ;(clojure.java.io/file "/tmp") ;cwd
                 )
        out (.getInputStream p)         ; my in is your out
        err (.getErrorStream p)]
    out))
