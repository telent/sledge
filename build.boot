(set-env!
 :resource-paths #{"resources"}
 :source-paths #{"src" "cljs"}
 :dependencies '[[org.clojure/clojure "1.8.0"]
                 [com.cemerick/piggieback "0.2.1"]
                 [org.clojure/tools.nrepl "0.2.12"]
                 [weasel "0.7.0" :exclusions [org.clojure/clojurescript]]
                 [green-tags "0.3.0-alpha"]
                 [hiccup "1.0.5"]
                 [boot-deps "0.1.6"]
                 [aleph "0.4.1"]
                 [ring "1.5.0"  :exclusions [org.clojure/java.classpath]]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojure/clojurescript "1.9.229"]
                 [org.clojure/core.async "0.2.391"]
                 [org.omcljs/om "1.0.0-alpha44"]
                 [sablono "0.7.4"]
                 [juxt/dirwatch "0.2.3"]])

(require '[sledge.boot-build :refer :all])
(require '[weasel.repl.websocket])
(require '[cemerick.piggieback])

(task-options!
 pom {:project 'sledge
      :version "0.1.1"}
 jar {:main 'sledge.core}
 cljs {:main 'sledge.core
       :optimizations :whitespace
       :options {}
       :output-file "assets/js/main.js"}
 target {:dir #{"target/"}})

(deftask pig
  "Piggieback nrepl middleware"
  []
  (swap! @(resolve 'boot.repl/*default-middleware*)
         concat '[cemerick.piggieback/wrap-cljs-repl])
  identity)

(defn wait-for-browser-repl []
  (cemerick.piggieback/cljs-repl
   (weasel.repl.websocket/repl-env :ip "0.0.0.0" :port 9001)))

(deftask build []
  (comp
   (aot :namespace #{'sledge.core})
   (pom)
   (cljs :optimizations :advanced)
   (uber)
   (jar)
   (sift :include #{#"project.jar$"})
   (target)))
