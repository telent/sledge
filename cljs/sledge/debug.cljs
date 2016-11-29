(ns sledge.debug
 (:require [sledge.core :as core]))

(defn dump-state []
  (.log js/console "app-state is" (clj->js @core/app-state))
  (.log js/console "audio is " (core/player-el)))

(aset js/window "dumpState" dump-state)
