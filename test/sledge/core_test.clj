(ns sledge.core-test
  (:require [clojure.test :refer :all]
            [clj-webdriver.taxi :as tx]
            [clj-webdriver.firefox :as ff]
            [sledge.core :refer :all]))


(deftest the-tests
  (testing  "Server responds"
    (tx/set-driver! {:browser :firefox
                     :profile (ff/new-profile)
                     } "http://localhost:53281")
    (let [input (tx/element "input#search-term")]
      (is (tx/displayed? input ) true)
      (tx/input-text input "queen"))

    (tx/wait-until #(> (count (tx/elements "div.results div.track")) 1))

    (let [els (tx/elements "div.results div.track")
          choose-rows (map #(nth els %) [2 4 6 11])]
      (dorun (map #(tx/click (tx/find-element-under % {:tag :button}))
                  choose-rows)))

    (tx/wait-until #(= 5 (count (tx/elements "div.queue div.track"))))

    (let [audio-url (tx/attribute "audio" :src)]
      (is (.startsWith audio-url "http://localhost:53281/bits/"))
      (let [del (nth (tx/elements "div.queue div.track") 2)]
        (tx/click (tx/find-element-under del {:tag :button}))
        (tx/wait-until #(= 4 (count (tx/elements "div.queue div.track"))))
        (is (= (tx/attribute "audio" :src) audio-url)
            "deleting 2nd track doesn't interrupt playback"))
      (let [del (nth (tx/elements "div.queue div.track") 1)]
        (tx/click (tx/find-element-under del {:tag :button}))
        (tx/wait-until #(= 3 (count (tx/elements "div.queue div.track"))))
        (is (not (= (tx/attribute "audio" :src) audio-url))
            "deleting 1st track moves player onto second"))
      )))

;; (clojure.test/run-tests 'sledge.core-test)
