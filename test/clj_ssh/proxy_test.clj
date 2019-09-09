(ns clj-ssh.proxy-test
  (:require
   [clojure.test :refer :all]
   [clj-ssh.proxy :refer :all]
   [clj-ssh.ssh :refer [session ssh ssh-agent with-connection]]))

(deftest ^:proxy proxy-test
  ;; requires a SOCS proxy available on localhost port 1080
  (let [agent (ssh-agent {})
        proxy (socks5-proxy "localhost" 1080)
        session (session agent "127.0.0.1" {:strict-host-key-checking :no})]
    (set-session-proxy session proxy)
    (with-connection session
      (let [result (ssh session {:in "echo hello"})]
        (println (result :out))))))
