(set-env!
 :resource-paths #{"resources"}
 :source-paths #{"src" "cljs"}
 :dependencies '[[org.clojure/clojure "1.8.0"]
                 [green-tags "0.3.0-alpha"]
                 [hiccup "1.0.5"]
                 [boot-deps "0.1.6"]
                 [aleph "0.4.1"]
                 [ring "1.5.0"  :exclusions [org.clojure/java.classpath]]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojure/clojurescript "1.9.229"]
                 [org.clojure/core.async "0.2.391"]
                 [org.omcljs/om "0.9.0"]
                 [juxt/dirwatch "0.2.3"]])

(require '[sledge.boot-build :refer :all])

(task-options!
 pom {:project 'sledge
      :version "0.1.1"}
 jar {:main 'sledge.core}
 cljs {:main 'sledge.core
       :options {:optimizations :advanced}
       :output-file "assets/js/main.js"}
 target {:dir #{"target/"}})


(deftask build []
  (comp
   (aot :namespace #{'sledge.core})
   (pom)
   (cljs)
   (uber)
   (jar)
   (sift :include #{#"project.jar$"})
   (target)))
