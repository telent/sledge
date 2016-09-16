(ns sledge.boot-build
  (:require [boot.core :as core]
            [boot.util :as util]
            [cljs.build.api :as cljsc]
            [boot.task.built-in :as task]))

(core/deftask cljs
  "build clojurescript files"
  [m main MAIN sym "main namespace"
   f output-file PATH str "output filename"
   o options OPTS edn "options for the Clojurescript compiler"]
  (let [target (core/tmp-dir!)]
    (core/with-pre-wrap [fileset]
      (let [inputs (some->> (core/input-files fileset)
                            (core/by-ext [".cljs"])
                            (map core/tmp-file))
            out-name (or (:output-file *opts*) "main.js")
            opts (assoc (or (:options *opts*) {})
                        :output-to (.getPath
                                    (clojure.java.io/file
                                     target
                                     out-name))
                        :output-dir (.getPath target))]
        (when (seq inputs)
          (util/info "Compiling %d Clojurescript sources...\n" (count inputs))
          (cljsc/build (apply cljsc/inputs inputs) opts)))
      (-> fileset (core/add-resource target) core/commit!))))
