(defproject clj-ssh "0.5.1-SNAPSHOT"
  :description "Library for using SSH from clojure."
  :url "https://github.com/hugoduncan/clj-ssh"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/tools.logging "0.1.2"
                  :exclusions [org.clojure/clojure]]
                 [jsch-agent-proxy "0.0.4"]
                 [jsch-agent-proxy/jsch-agent-proxy-jna "0.0.4"
                  :exclusions [com.jcraft/jsch-agent-proxy]]
                 [com.jcraft/jsch "0.1.49"]])
