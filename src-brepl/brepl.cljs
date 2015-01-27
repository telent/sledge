(ns sledge.brepl
  (:require [simple-brepl.client]))

(if (.-log js/console)
  (println "hey from brepl"))
