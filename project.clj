(defproject org.clj-commons/clj-ssh
  (or (System/getenv "PROJECT_VERSION") "0.6.0-SNAPSHOT")
  :description "Library for using SSH from clojure."
  :url "https://github.com/clj-commons/clj-ssh"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :deploy-repositories [["clojars" {:url "https://repo.clojars.org"
                                    :username :env/clojars_username
                                    :password :env/clojars_org_clj_commons_password
                                    :sign-releases true}]]

  :dependencies [[org.clojure/tools.logging "1.2.4"
                  :exclusions [org.clojure/clojure]]
                 [com.github.mwiede/jsch "0.2.15"]
                 [net.java.dev.jna/jna "5.14.0"]
                 [com.kohlschutter.junixsocket/junixsocket-core "2.8.3" :extension "pom"]]
  :jvm-opts ["-Djava.awt.headless=true"]
  :profiles {:provided {:dependencies [[org.clojure/clojure "1.10.1"]]}})

