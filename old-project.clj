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
                 [ring "1.3.2"  :exclusions [org.clojure/java.classpath]]

                 [org.clojure/data.json "0.2.5"]
                 [org.clojure/clojurescript "0.0-2727"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.omcljs/om "0.8.6"]
                 [juxt/dirwatch "0.2.2"]]
  :plugins [[lein-cljsbuild "1.0.3"]]
  :main ^:skip-aot sledge.core
  :resource-paths ["resources/"]
  :target-path "target/%s"
  :profiles {:uberjar
             {:aot :all
              :cljsbuild
              {
               :builds [{:id "production"
                         :source-paths ["src-cljs"]
                         :compiler
                         {
                          :main sledge.core
                          :output-to "resources/production-out/main.js"
                          :output-dir "resources/production-out"
                          :optimizations :advanced
                          :pretty-print false
                          }}]
               }}
             :dev {:dependencies
                   [[org.clojure/core.cache "0.6.4"]
                    [org.seleniumhq.selenium/selenium-java "2.44.0"
                     :exclusions [org.eclipse.jetty/jetty-io
                                  org.eclipse.jetty/jetty-http]]
                    [org.seleniumhq.selenium/selenium-server "2.44.0"
                     :exclusions [org.eclipse.jetty/jetty-io
                                  org.eclipse.jetty/jetty-http]]
                    [org.seleniumhq.selenium/selenium-remote-driver "2.44.0"
                     :exclusions [org.eclipse.jetty/jetty-io
                                  org.eclipse.jetty/jetty-http]]
                    [clj-webdriver "0.6.1"
                     :exclusions [org.eclipse.jetty/jetty-io
                                  org.eclipse.jetty/jetty-http
                                  ]]
                    ]
                   }
             :brepl {
                     ;; uberjar appears to include the dev profile, I don't
                     ;; understand why, so we use the brepl profile for
                     ;; actual dev
                     :cljsbuild {
                                 :builds
                                 [{:id "dev"
                                   :source-paths ["src-cljs" "src-brepl" ]
                                   :compiler {
                                              :main sledge.core
                                              :output-to "resources/out/main.js"
                                              :output-dir "resources/out"
                                              :optimizations :whitespace
                                              :pretty-print true
                                              :source-map "resources/out/main.map"
                                              }}
                                  ]}
                     :jvm-opts ["-Denable_brepl=true"]
                     :dependencies [[clojure-complete "0.2.4"]
                                    [org.clojure/tools.nrepl "0.2.7"]]
                     :plugins [[jarohen/simple-brepl "0.2.1"]]
                     }}
  )
