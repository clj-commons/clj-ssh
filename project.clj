(defproject clj-ssh "0.2.0-SNAPSHOT"
  :description "ssh from clojure"
  :dependencies [[org.clojure/clojure "1.1.0"]
                 [org.clojure/clojure-contrib "1.1.0"]
                 [com.jcraft/jsch "0.1.42"]]
  :dev-dependencies [[swank-clojure "1.2.1"]
                     [autodoc "0.7.0"]
                     [log4j/log4j "1.2.14"]]
  :autodoc {:name "clj-ssh"
            :description "Library for using SSH from clojure."
            :copyright "Copyright Hugo Duncan 2010. All rights reserved."
            :web-src-dir "http://github.com/hugoduncan/clj-ssh/blob/"
            :web-home "http://hugoduncan.github.com/clj-ssh/" })
