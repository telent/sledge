(ns sledge.transcode
  (:import [java.lang Runtime Process]))

;;  avconv -i /srv/media/Music/flac/Delerium-Karma\ Disc\ 1/04.Silence.flac -f ogg -c libvorbis pipe: |cat >s.ogg


(defn avconv [filename format]
  (let [rt (Runtime/getRuntime)
        bin (or (System/getenv "AVCONV") "/usr/bin/avconv")
        codec (get {"ogg" "libvorbis"
                    "mp3" "libmp3lame"}
                   format)
        args  [bin
               "-i" filename
               "-f" format
               "-codec:a" codec
               "pipe:"]
        p (.exec rt (into-array args))
        out (.getInputStream p)         ; my in is your out
        err (.getErrorStream p)]
    out))
