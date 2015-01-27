(ns sledge.brepl
  (:require [simple-brepl.client]))

(if (.-log js/console)
  (.log js/console "hey from brepl"))
