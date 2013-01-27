{:dev {:dependencies [[ch.qos.logback/logback-classic "1.0.0"]]}
 :clojure-1.2.1 {:dependencies [[org.clojure/clojure "1.2.1"]]}
 :clojure-1.3.0 {:dependencies [[org.clojure/clojure "1.3.0"]]}
 :clojure-1.4.0 {:dependencies [[org.clojure/clojure "1.4.0"]]}
 :clojure-1.5.0 {:dependencies [[org.clojure/clojure "1.5.0-RC3"]]}
 :codox {:codox {:writer codox-md.writer/write-docs
                 :version "0.4"
                 :output-dir "doc/api/0.4"
                 :exclude [clj-ssh.agent clj-ssh.reflect clj-ssh.keychain]}
         :dependencies [[codox-md "0.1.0" :exclusions [org.clojure/clojure]]]}
 :marginalia {:marginalia {:dir "doc/api/0.4"}}
 :release
 {:plugins [[lein-set-version "0.2.1"]]
  :set-version
  {:updates [{:path "README.md"
              :no-snapshot true
              :search-regex #"clj-ssh \"\d+\.\d+\.\d+\""}]}}}
