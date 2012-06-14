(defproject clj-ssh "0.4.0-SNAPSHOT"
  :description "Library for using SSH from clojure."
  :dependencies [[org.clojure/clojure "1.2.1"]
                 [org.clojure/tools.logging "0.1.2"]
                 [jsch-agent-proxy "0.0.4"]
                 [jsch-agent-proxy/jsch-agent-proxy-jna "0.0.4"]
                 [slingshot "0.10.2"]
                 [com.jcraft/jsch "0.1.48"]]
  :dev-dependencies [[org.slf4j/slf4j-api "1.6.1"]
                     [ch.qos.logback/logback-core "1.0.0"]
                     [ch.qos.logback/logback-classic "1.0.0"]]
  :profiles {:dev {:dependencies [[org.slf4j/slf4j-api "1.6.1"]
                                  [ch.qos.logback/logback-core "1.0.0"]
                                  [ch.qos.logback/logback-classic "1.0.0"]
                                  [codox-md "0.1.0"]]}}
  :multi-deps {"slingshot-0.10.1" [[slingshot "0.10.1"]
                                   [org.clojure/clojure "1.2.1"]]
               "clojure-1.2.1" [[slingshot "0.10.1"]
                                [org.clojure/clojure "1.2.1"]]
               "clojure-1.3.0" [[slingshot "0.10.1"]
                                [org.clojure/clojure "1.3.0"]]
               "clojure-1.4.0" [[slingshot "0.10.1"]
                                [org.clojure/clojure "1.4.0-beta1"]]}
  :codox {:writer codox-md.writer/write-docs
          :version "0.4"
          :output-dir "doc/api/0.4"})
