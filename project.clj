(defproject clj-ssh "0.5.3-SNAPSHOT"
  :description "Library for using SSH from clojure."
  :url "https://github.com/hugoduncan/clj-ssh"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/tools.logging "0.1.2"
                  :exclusions [org.clojure/clojure]]
                 [com.jcraft/jsch.agentproxy.usocket-jna "0.0.5"]
                 [com.jcraft/jsch.agentproxy.usocket-nc "0.0.5"]
                 [com.jcraft/jsch.agentproxy.sshagent "0.0.5"]
                 [com.jcraft/jsch.agentproxy.pageant "0.0.5"]
                 [com.jcraft/jsch.agentproxy.core "0.0.5"]
                 [com.jcraft/jsch.agentproxy.jsch "0.0.5"]
                 [com.jcraft/jsch "0.1.49"]])
