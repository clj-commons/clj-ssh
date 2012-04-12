(defproject clj-ssh "0.3.2-SNAPSHOT"
  :description "Library for using SSH from clojure."
  :dependencies [[org.clojure/clojure "1.2.0"]
                 [org.clojure/tools.logging "0.1.2"]
                 [slingshot "0.2.0"]
                 [com.jcraft/jsch "0.1.44-1"]]
  :dev-dependencies [[log4j/log4j "1.2.14"]]
  :multi-deps {"slingshot-0.10.1" [[slingshot "0.10.1"]
                                   [org.clojure/clojure "1.2.1"]]
               "clojure-1.2.1" [[slingshot "0.10.1"]
                                [org.clojure/clojure "1.2.1"]]
               "clojure-1.3.0" [[slingshot "0.10.1"]
                                [org.clojure/clojure "1.3.0"]]
               "clojure-1.4.0" [[slingshot "0.10.1"]
                                [org.clojure/clojure "1.4.0-beta1"]]}
  :codox {:writer codox-md.writer/write-docs
          :version "0.3"
          :output-dir "doc/api/0.3"})
