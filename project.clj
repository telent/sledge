(defproject sledge "0.1.0-SNAPSHOT"
  :description "Sledge: lost in music"
  :url "http://example.com/FIXME"
  :license {:name "GNU Affero General Public License"
            :comments "Commercial licensing enquiries welcome, contact the author"
            :url "https://gnu.org/licenses/agpl.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [green-tags "0.3.0-alpha"]
                 [hiccup "1.0.5"]
                 [http-kit "2.1.16"]
                 [ring "1.3.1"]
                 [org.clojure/data.json "0.2.5"]
                 [clucy "0.4.0"]]
  :main ^:skip-aot sledge.core
  :resource-paths ["resources/"]
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
