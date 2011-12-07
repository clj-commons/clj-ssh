(defproject clj-ssh "0.3.0"
  :description "ssh from clojure"
  :dependencies [[org.clojure/clojure "1.2.0"]
                 [org.clojure/tools.logging "0.1.2"]
                 [slingshot "0.2.0"]
                 [com.jcraft/jsch "0.1.44-1"]]
  :dev-dependencies [[swank-clojure "1.2.1"]
                     [org.clojars.weavejester/autodoc "0.9.0"]
                     [log4j/log4j "1.2.14"]]
  :autodoc {:name "clj-ssh"
            :description "Library for using SSH from clojure."
            :copyright "Copyright Hugo Duncan 2010, 2011. All rights reserved."
            :web-src-dir "http://github.com/hugoduncan/clj-ssh/blob/"
            :web-home "http://hugoduncan.github.com/clj-ssh/"})
