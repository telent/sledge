(ns sledge.transcode
  (:import [java.lang Runtime Process]))

;;  avconv -i /srv/media/Music/flac/Delerium-Karma\ Disc\ 1/04.Silence.flac -f ogg -c libvorbis pipe: |cat >s.ogg


(defn avconv [filename format]
  (let [rt (Runtime/getRuntime)
        bin (or (System/getenv "AVCONV") "/usr/bin/avconv")
        codec (get {"ogg" "libvorbis"
                    "mp3" "libmp3lame"}
                   format)
        p (.exec rt
                 (into-array
                  [bin
                   "-i"
                   filename
                   "-f"
                   format
                   "-c"
                   codec
                   "pipe:"])
                 )
        out (.getInputStream p)         ; my in is your out
        err (.getErrorStream p)]
    out))
