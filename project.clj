(defproject clj-commons/clj-ssh "0.6.0-SNAPSHOT"
  :description "Library for using SSH from clojure."
  :url "https://github.com/clj-commons/clj-ssh"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/tools.logging "1.2.4"
                  :exclusions [org.clojure/clojure]]
                 [com.github.mwiede/jsch "0.2.9"]
                 [net.java.dev.jna/jna "5.13.0"]
                 [com.kohlschutter.junixsocket/junixsocket-core "2.6.2" :extension "pom"]]
  :jvm-opts ["-Djava.awt.headless=true"]
  :profiles {:provided {:dependencies [[org.clojure/clojure "1.10.1"]]}})

