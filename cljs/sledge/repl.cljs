(ns sledge.repl
  (:require [weasel.repl :as r]))

(defn run-repl []
  (when-not (r/alive?)
    (r/connect "ws://localhost:9001")))


(.addEventListener js/window "load" run-repl)
(aset js/window "repl" run-repl)
