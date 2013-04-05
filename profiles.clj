{:dev {:dependencies [[ch.qos.logback/logback-classic "1.0.0"]]}
 :clojure-1.2.1 {:dependencies [[org.clojure/clojure "1.2.1"]]}
 :clojure-1.3.0 {:dependencies [[org.clojure/clojure "1.3.0"]]}
 :clojure-1.4.0 {:dependencies [[org.clojure/clojure "1.4.0"]]}
 :clojure-1.5.0 {:dependencies [[org.clojure/clojure "1.5.0-RC4"]]}
 :doc {:dependencies [[com.palletops/pallet-codox "0.1.0-SNAPSHOT"]
                      [codox-md "0.2.0" :exclusions [org.clojure/clojure]]]
       :plugins [[codox/codox.leiningen "0.6.4"]
                 [lein-marginalia "0.7.1"]]
       :codox {:writer codox-md.writer/write-docs
               :output-dir "doc/0.5/api"
               :src-dir-uri "https://github.com/hugoduncan/clj-ssh/blob/develop"
               :src-linenum-anchor-prefix "L"}
       :aliases {"marg" ["marg" "-d" "doc/0.5/annotated"]
                 "codox" ["doc"]
                 "doc" ["do" "codox," "marg"]}}
 :release
 {:plugins [[lein-set-version "0.2.1"]]
  :set-version
  {:updates [{:path "README.md"
              :no-snapshot true
              :search-regex #"clj-ssh \"\d+\.\d+\.\d+\""}]}}}
