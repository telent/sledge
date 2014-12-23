(ns sledge.core-test
  (:require [clojure.test :refer :all]
            [clj-webdriver.taxi :as tx]
            [clj-webdriver.firefox :as ff]
            [sledge.core :refer :all]))

(defn search-tracks [text]
  (let [input (tx/element "input#search-term")]
    (is (tx/displayed? input ) true)
    (tx/input-text input (str text "\r")))
  (tx/wait-until #(> (count (tx/elements "div.results div.track")) 1)))

(defn click-track-button [el]
  (tx/click (tx/find-element-under el {:tag :button})))

(tx/set-driver! {:browser :firefox
                 :profile (ff/new-profile)
                 }
                "http://localhost:53281")

(deftest the-tests
  (testing  "Server responds"
    (search-tracks "queen")
    (let [els (tx/elements "div.results div.track")
          choose-rows (map #(nth els %) [2 4 6 11])]
      (dorun (map click-track-button choose-rows)))

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

(defn search-for-text [text]
  (search-tracks text)
  (tx/wait-until #(> (count (results)) 1))
  (tx/wait-until (fn []
                   (some? #(matches (content %) (str "_content: " text))
                          (filters)))))

(defn find-track-by-artist [artist]
  (first
   (filter #(= (tx/text (tx/find-element-under % {:class "artist"}))
               artist)
           (tx/elements "div.results.tracks div.track"))))

(deftest the-more-tests
  (testing  "search for text"
    (search-tracks "queen")
    (let [row (find-track-by-artist "Queen")]
      (tx/click (tx/find-element-under row {:class :artist}))
      ;; assert all tracks have matching artist
      (tx/click (tx/find-element-under row {:class :album}))
      ;; assert all tracks have matching album
      ;; - verify three tags rendered
      ;; - click on a tag
      ;; - verify it has been removed

      )))

(comment
(clojure.test/run-tests 'sledge.core-test)
)
