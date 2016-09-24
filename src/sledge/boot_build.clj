(ns sledge.boot-build
  (:require [boot.core :as core]
            [boot.util :as util]
            [cljs.build.api :as cljsc]
            [clojure.java.io :as io]
            [boot.task.built-in :as task]))

(core/deftask cljs
  "build clojurescript files"
  [m main MAIN sym "main namespace"
   f output-file PATH str "output filename"
   O optimizations KEY kw "Google Closure optimization level (none, whitespace, simple, advanced)"
   o options OPTS edn "other options for the Clojurescript compiler"]
  (let [target (core/tmp-dir!)]
    (core/with-pre-wrap [fileset]
      (let [inputs (some->> (core/input-files fileset)
                            (core/by-ext [".cljs"])
                            (map core/tmp-file))
            out-name (or output-file "main.js")
            opts (assoc (or options {})
                        :optimizations optimizations
                        :output-to (.getPath (io/file target out-name))
                        :output-dir (.getPath target))]
        (when (seq inputs)
          (util/info
           "Compiling %d Clojurescript sources (optimization \"%s\")... "
           (count inputs) (name optimizations))
          (cljsc/build (apply cljsc/inputs inputs) opts)
          (util/info "done\n")))
      (-> fileset (core/add-resource target) core/commit!))))
