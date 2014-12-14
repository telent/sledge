(defproject sledge "0.1.0-SNAPSHOT"
  :description "Sledge: lost in music"
  :url "http://example.com/FIXME"
  :license {:name "GNU Affero General Public License"
            :comments "Commercial licensing enquiries welcome, contact the author"
            :url "https://gnu.org/licenses/agpl.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [green-tags "0.3.0-alpha"]
                 [hiccup "1.0.5"]
                 [aleph "0.4.0-alpha9"]
                 [ring "1.3.2"]
                 [org.clojure/data.json "0.2.5"]
                 [org.clojure/clojurescript "0.0-2411"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [om "0.8.0-alpha2"]
                 [clucy "0.4.0"]]
  :plugins [[lein-cljsbuild "1.0.3"]]
  :hooks [leiningen.cljsbuild]
  :cljsbuild {
              :builds [{:id "dev"
                        :source-paths ["src-cljs"]
                        :compiler {
                                   :output-to "resources/out/main.js"
                                   :output-dir "resources/out"
                                   :optimizations :none
                                   :source-map true}}

                       {:id "release"
                        :source-paths ["src-cljs"]
                        :compiler {
                                   :output-to "resources/production-out/main.js"
                                   :output-dir "resources/production-out"
                                   :optimizations :advanced
                                   :pretty-print false
                                   :preamble ["react/react.min.js"]
                                   :externs ["react/externs/react.js"]
                                   }}]}
  :main ^:skip-aot sledge.core
  :resource-paths ["resources/"]
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}
             :dev {:dependencies [[org.clojure/core.cache "0.6.4"]
                                  [org.seleniumhq.selenium/selenium-java "2.44.0"]
                                  [org.seleniumhq.selenium/selenium-server "2.44.0"]
                                  [org.seleniumhq.selenium/selenium-remote-driver "2.44.0"]

                                  [clj-webdriver "0.6.1"]]}}
  )
