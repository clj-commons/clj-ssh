(ns clj-ssh.ssh-test
  (:use clj-ssh.ssh)
  (:use clojure.test)
  (:require [clojure.java.io :as io])
  (:import com.jcraft.jsch.JSch))

(def debug-log-levels
  {com.jcraft.jsch.Logger/DEBUG :debug
   com.jcraft.jsch.Logger/INFO  :debug
   com.jcraft.jsch.Logger/WARN  :debug
   com.jcraft.jsch.Logger/ERROR :error
   com.jcraft.jsch.Logger/FATAL :fatal})

(use-fixtures :once (fn [f]
                      (let [levels @ssh-log-levels]
                        (try
                          (reset! ssh-log-levels debug-log-levels)
                          (f)
                          (finally
                           (reset! ssh-log-levels levels))))))

(defmacro with-private-vars [[ns fns] & tests]
  "Refers private fns from ns and runs tests in context.  From users mailing
list, Alan Dipert and MeikelBrandmeyer."
  `(let ~(reduce #(conj %1 %2 `@(ns-resolve '~ns '~%2)) [] fns)
     ~@tests))

;; TEST SETUP
;;
;; The tests assume the following setup
;;
;; ssh-keygen -f ~/.ssh/clj_ssh -t rsa -C "key for test clj-ssh" -N ""
;; ssh-keygen -f ~/.ssh/clj_ssh_pp -t rsa -C "key for test clj-ssh" -N "clj-ssh"
;; cp ~/.ssh/authorized_keys ~/.ssh/authorized_keys.bak
;; echo "from=\"localhost\" $(cat ~/.ssh/clj_ssh.pub)" >> ~/.ssh/authorized_keys
;; echo "from=\"localhost\" $(cat ~/.ssh/clj_ssh_pp.pub)" >> ~/.ssh/authorized_keys

(defn private-key-path
  [] (str (. System getProperty "user.home") "/.ssh/clj_ssh"))

(defn public-key-path
  [] (str (. System getProperty "user.home") "/.ssh/clj_ssh.pub"))

(defn encrypted-private-key-path
  [] (str (. System getProperty "user.home") "/.ssh/clj_ssh_pp"))

(defn encrypted-public-key-path
  [] (str (. System getProperty "user.home") "/.ssh/clj_ssh_pp.pub"))

(defn username
  [] (or (. System getProperty "ssh.username")
         (. System getProperty "user.name")))

(defn cwd
  [] (. System getProperty "user.dir"))

(defn home
  [] (. System getProperty "user.home"))

(defn with-test-private-key-path
  [f]
  (binding [clj-ssh.ssh/*default-identity* (private-key-path)]
    (f)))

(use-fixtures :once with-test-private-key-path)

(with-private-vars [clj-ssh.ssh [file-path camelize]]

  (deftest file-path-test
    (is (= "abc" (file-path "abc")))
    (is (= "abc" (file-path (java.io.File. "abc")))))

  (deftest camelize-test
    (is (= "StrictHostKeyChecking" (camelize "strict-host-key-checking")))))


;; (deftest default-identity-test
;;   (is (= (str (. System getProperty "user.home") "/.ssh/id_rsa")
;;          (.getPath (default-identity)))))

(deftest default-session-options-test
  (let [old *default-session-options*
        new {:strict-host-key-checking :no}]
    (default-session-options new)
    (is (= new *default-session-options*))
    (default-session-options old)
    (is (= old *default-session-options*))))

(deftest ssh-agent?-test
  (is (ssh-agent? (JSch.)))
  (is (not (ssh-agent? "i'm not an ssh-agent"))))

(deftest create-ssh-agent-test
  (is (ssh-agent? (create-ssh-agent false)))
  (is (ssh-agent? (create-ssh-agent (private-key-path)))))

(deftest with-ssh-agent-test
  (with-ssh-agent []
    (is (ssh-agent? *ssh-agent*))
    (is (= 1 (count (.getIdentityNames *ssh-agent*)))))
  (with-ssh-agent [false]
    (is (ssh-agent? *ssh-agent*))
    (is (= 0 (count (.getIdentityNames *ssh-agent*)))))
  (with-ssh-agent [(private-key-path)]
    (is (ssh-agent? *ssh-agent*)))
  (let [agent (create-ssh-agent)]
    (with-ssh-agent [agent]
      (is (= *ssh-agent* agent)))))

(deftest make-identity-test
  (with-ssh-agent [false]
    (let [path (private-key-path)
          identity (make-identity path (str path ".pub"))]
      (is (instance? com.jcraft.jsch.Identity identity))
      (is (not (.isEncrypted identity)))
      (add-identity identity)
      (is (= 1 (count (.getIdentityNames *ssh-agent*)))))
    (let [path (encrypted-private-key-path)
          identity (make-identity path (str path ".pub"))]
      (is (instance? com.jcraft.jsch.Identity identity))
      (is (.isEncrypted identity))
      (add-identity identity "clj-ssh")
      (is (= 2 (count (.getIdentityNames *ssh-agent*)))))))

(deftest add-identity-test
  (let [key (private-key-path)]
    (with-ssh-agent [false]
      (add-identity key)
      (is (= 1 (count (.getIdentityNames *ssh-agent*))))
      (add-identity *ssh-agent* key)))
  (testing "passing byte arrays"
    (with-ssh-agent [false]
      (add-identity
       *ssh-agent*
       "name"
       (.getBytes (slurp (private-key-path)))
       (.getBytes (slurp (public-key-path)))
       nil)
      (is (= 1 (count (.getIdentityNames *ssh-agent*))))
      (is (= "name" (first (.getIdentityNames *ssh-agent*))))))
  (testing "ssh-agent"
    (with-ssh-agent (ssh-agent)
      (let [n (count (.getIdentityNames *ssh-agent*))]
        (add-identity
         *ssh-agent*
         "name"
         (.getBytes (slurp (private-key-path)))
         (.getBytes (slurp (public-key-path)))
         nil)
        (is (= (inc n) (count (.getIdentityNames *ssh-agent*)))))
      (is (some #(= "name" %) (.getIdentityNames *ssh-agent*))))))

(deftest has-identity?-test
  (let [key (private-key-path)]
    (with-ssh-agent [false]
      (is (not (has-identity? key)))
      (is (not (has-identity? *ssh-agent* key)))
      (add-identity key)
      (is (= 1 (count (.getIdentityNames *ssh-agent*))))
      (is (has-identity? key))
      (is (has-identity? *ssh-agent* key))
      (add-identity *ssh-agent* key))))

(with-private-vars [clj-ssh.ssh [session-impl]]
  (deftest session-impl-test
    (with-ssh-agent []
      (let [session
            (session-impl *ssh-agent* "somehost" (username) nil nil {})]
        (is (instance? com.jcraft.jsch.Session session))
        (is (not (connected? session))))
      (let [session
            (session-impl *ssh-agent* "localhost" (username) nil nil
                          {:strict-host-key-checking :no})]
        (is (instance? com.jcraft.jsch.Session session))
        (is (not (connected? session)))
        (is (= "no" (.getConfig session "StrictHostKeyChecking")))))))

(deftest session-test
  (with-ssh-agent []
    (let [session (session *ssh-agent* "localhost"
                           :username (username) :port 22)]
      (is (instance? com.jcraft.jsch.Session session))
      (is (not (connected? session)))))
  (with-ssh-agent []
    (let [session (session "localhost" :username (username))]
      (is (instance? com.jcraft.jsch.Session session))
      (is (not (connected? session)))))
  (with-ssh-agent []
    (let [session (session *ssh-agent* "localhost" :username (username))]
      (is (instance? com.jcraft.jsch.Session session))
      (is (not (connected? session))))))

(deftest session-connect-test
  (with-ssh-agent [false]
    (add-identity (private-key-path))
    (let [session (session "localhost" :username (username)
                           :strict-host-key-checking :no)]
      (is (instance? com.jcraft.jsch.Session session))
      (is (not (connected? session)))
      (connect session)
      (is (connected? session))
      (disconnect session)
      (is (not (connected? session))))
    (let [session (session "localhost" :username (username)
                           :strict-host-key-checking :no)]
      (with-connection session
        (is (connected? session)))
      (is (not (connected? session)))))
  (with-ssh-agent [false]
    (add-identity (encrypted-private-key-path) "clj-ssh")
    (let [session (session "localhost" :username (username)
                           :strict-host-key-checking :no)]
      (is (instance? com.jcraft.jsch.Session session))
      (is (not (connected? session)))
      (connect session)
      (is (connected? session))
      (disconnect session)
      (is (not (connected? session))))
    (let [session (session "localhost" :username (username)
                           :strict-host-key-checking :no)]
      (with-connection session
        (is (connected? session)))
      (is (not (connected? session))))))

(deftest open-shell-channel-test
  (with-ssh-agent [false]
    (add-identity (private-key-path))
    (let [session (session "localhost" :username (username)
                           :strict-host-key-checking :no)]
      (with-connection session
        (let [shell (open-channel session :shell)
              os (java.io.ByteArrayOutputStream.)]
          (.setInputStream
           shell
           (java.io.ByteArrayInputStream. (.getBytes "ls /;exit 0;\n"))
           false)
          (.setOutputStream shell os)
          (with-connection shell
            (while (connected? shell)
                   (Thread/sleep 1000))
            (is (.contains (str os) "bin"))))))))

(deftest ssh-shell-test
  (with-ssh-agent [false]
    (add-identity (private-key-path))
    (let [session (session "localhost" :username (username)
                           :strict-host-key-checking :no)]
      (with-connection session
        (let [result (ssh-shell session "echo hello" "UTF-8" {})]
          (is (= 0 (first result)))
          (is (.contains (second result) "hello")))
        (let [result (ssh-shell session "echo hello" :bytes {})]
          (is (= 0 (first result)))
          (is (.contains (String. (second result)) "hello")))
        (let [result (ssh-shell session "echo hello;exit 1" "UTF-8" {})]
          (is (= 1 (first result)))
          (is (.contains (second result) "hello")))
        (let [[shell stream] (ssh-shell session "echo hello;exit 1" :stream {})]
          (while (connected? shell) (Thread/sleep 100))
          (is (= 1 (.getExitStatus shell)))
          (is (pos? (.available stream)))
          (let [bytes (byte-array 1024)
                n (.read stream bytes 0 1024)]
            (is (.contains (String. bytes 0 n)  "hello"))))
        (let [result (ssh-shell session "exit $(tty -s)" "UTF-8" {:pty true})]
          (is (= 0 (first result))))
        (let [result (ssh-shell session "exit $(tty -s)" "UTF-8" {:pty nil})]
          (is (= 1 (first result))))
        (let [result (ssh-shell session "ssh-add -l" "UTF-8" {})]
          (is (pos? (first result))))
        (let [result (ssh-shell session "ssh-add -l" "UTF-8" {:agent-forwarding false})]
          (is (pos? (first result))))
        (let [result (ssh-shell session "ssh-add -l" "UTF-8" {:agent-forwarding true})]
          (is (re-find #"RSA" (second result)))
          (is (= 0 (first result))))))))

(deftest ssh-exec-test
  (with-ssh-agent [false]
    (add-identity (private-key-path))
    (let [session (session "localhost" :username (username)
                           :strict-host-key-checking :no)]
      (with-connection session
        (let [result (ssh-exec session "/bin/bash -c 'ls /'" nil "UTF-8" {})]
          (is (= 0 (first result)))
          (is (.contains (second result) "bin"))
          (is (= "" (last result))))
        (let [result (ssh-exec
                      session "/bin/bash -c 'lsxxxxx /'" nil "UTF-8" {})]
          (is (not (= 0 (first result))))
          (is (= "" (second result)))
          (is (.contains (last result) "command not found")))
        (let [[exec out err] (ssh-exec
                              session
                              "/bin/bash -c 'ls / && lsxxxxx /'"
                              nil :stream {})]
          (while (connected? exec) (Thread/sleep 100))
          (is (not= 0 (.getExitStatus exec)))
          (is (pos? (.available out)))
          (is (pos? (.available err)))
          (let [out-bytes (byte-array 1024)
                out-n (.read out out-bytes 0 1024)
                err-bytes (byte-array 1024)
                err-n (.read err err-bytes 0 1024)]
            (is (.contains (String. out-bytes 0 out-n) "bin"))
            (is (.contains
                 (String. err-bytes 0 err-n)
                 "command not found"))))))))

(deftest ssh-test
  (with-ssh-agent [false]
    (add-identity (private-key-path))
    (let [session (session "localhost" :username (username)
                           :strict-host-key-checking :no)]
      (with-connection session
        (let [result (ssh session :in "echo hello")]
          (is (= 0 (first result)))
          (is (.contains (second result) "hello")))
        (let [result (ssh session "/bin/bash -c 'ls /'")]
          (is (= 0 (first result)))
          (is (.contains (second result) "bin"))
          (is (= "" (last result))))))
    (let [result (ssh "localhost" :in "echo hello" :username (username))]
      (is (= 0 (first result)))
      (is (.contains (second result) "hello")))
    (let [result (ssh "localhost" "/bin/bash -c 'ls /'" :username (username))]
      (is (= 0 (first result)))
      (is (.contains (second result) "bin"))
      (is (= "" (last result))))
    (let [result (ssh "localhost" :in "tty -s" :pty true :username (username))]
      (is (= 0 (first result))))
    (let [result (ssh "localhost" :in "tty -s" :pty false :username (username))]
      (is (= 1 (first result))))
    (let [result (ssh "localhost" :in "ssh-add -l" :agent-forwarding true :username (username))]
      (is (zero? (first result)))))
  (with-default-session-options {:strict-host-key-checking :no}
    (with-default-identity (private-key-path)
      (let [result (ssh "localhost" :in "echo hello" :username (username))]
        (is (= 0 (first result)))
        (is (.contains (second result) "hello")))
      (let [result (ssh "localhost" :in "echo hello" :return-map true
                        :username (username))]
        (is (= 0 (result :exit)))
        (is (.contains (result :out) "hello")))
      (let [result (ssh "localhost" "/bin/bash -c 'ls /'" :username (username))]
        (is (= 0 (first result)))
        (is (.contains (second result) "bin"))
        (is (= "" (last result))))
      (let [result (ssh "localhost" "/bin/bash -c 'ls /'" :return-map true
                        :username (username))]
        (is (= 0 (result :exit)))
        (is (.contains (result :out) "bin"))
        (is (= "" (result :err)))))))

(deftest ssh-sftp-cmd-test
  (with-ssh-agent [false]
    (add-identity (private-key-path))
    (let [session (session "localhost" :username (username)
                           :strict-host-key-checking :no)]
      (with-connection session
        (let [channel (ssh-sftp session)
              dir (ssh-sftp-cmd channel :ls ["/"] {})]
          (ssh-sftp-cmd channel :cd ["/"] {})
          (is (= "/" (ssh-sftp-cmd channel :pwd [] {})))
          ;; value equality comparison on lsentry is borked
          (is (= (map str dir)
                 (map str (ssh-sftp-cmd channel :ls [] {})))))))))

(defn sftp-monitor
  "Create a SFTP progress monitor"
  []
  (let [operation (atom nil)
        source (atom nil)
        destination (atom nil)
        number (atom nil)
        done (atom false)
        progress (atom 0)
        continue (atom true)]
    [ (proxy [com.jcraft.jsch.SftpProgressMonitor] []
        (init [op src dest max]
              (do
                (reset! operation op)
                (reset! source src)
                (reset! destination dest)
                (reset! number max)
                (reset! done false)))
        (count [n]
               (reset! progress n)
               @continue)
        (end []
             (reset! done true)))
      [operation source destination number done progress continue]]))

(defn sftp-monitor-done [state]
  @(nth state 4))

(defn test-sftp-with [channel]
  (let [dir (sftp channel :ls "/")]
    (sftp channel :cd "/")
    (is (= "/" (sftp channel :pwd)))
    ;; value equality comparison on lsentry is borked
    (is (= (map str dir)
           (map str (sftp channel :ls))))
    (let [tmpfile1 (java.io.File/createTempFile "clj-ssh" "test")
          tmpfile2 (java.io.File/createTempFile "clj-ssh" "test")
          file1 (.getPath tmpfile1)
          file2 (.getPath tmpfile2)
          content "content"
          content2 "content2"]
      (try
       (.setWritable tmpfile1 true false)
       (.setWritable tmpfile2 true false)
       (io/copy content tmpfile1)
       (sftp channel :put file1 file2)
       (is (= content (slurp file2)))
       (io/copy content2 tmpfile2)
       (sftp channel :get file2 file1)
       (is (= content2 (slurp file1)))
       (sftp
        channel :put (java.io.ByteArrayInputStream. (.getBytes content)) file1)
       (is (= content (slurp file1)))
       (let [[monitor state] (sftp-monitor)]
         (sftp channel :put (java.io.ByteArrayInputStream. (.getBytes content))
               file2 :with-monitor monitor)
         (is (sftp-monitor-done state)))
       (is (= content (slurp file2)))
       (finally
        (.delete tmpfile1)
        (.delete tmpfile2))))))


(defn test-sftp-transient-with [channel & options]
  (let [dir (apply sftp channel :ls "/" options)]
    (apply sftp channel :cd "/" options)
    (is (not= "/" (apply sftp channel :pwd options)))
    (let [tmpfile1 (java.io.File/createTempFile "clj-ssh" "test")
          tmpfile2 (java.io.File/createTempFile "clj-ssh" "test")
          file1 (.getPath tmpfile1)
          file2 (.getPath tmpfile2)
          content "content"
          content2 "othercontent"]
      (try
       (.setWritable tmpfile1 true false)
       (.setWritable tmpfile2 true false)
       (io/copy content tmpfile1)
       (apply sftp channel :put file1 file2 options)
       (is (= content (slurp file2)))
       (io/copy content2 tmpfile2)
       (apply sftp channel :get file2 file1 options)
       (is (= content2 (slurp file1)))
       (apply sftp channel
              :put (java.io.ByteArrayInputStream. (.getBytes content))
              file1
              options)
       (is (= content (slurp file1)))
       (let [[monitor state] (sftp-monitor)]
         (apply sftp channel
                :put (java.io.ByteArrayInputStream. (.getBytes content))
                file2 :with-monitor monitor options)
         (is (sftp-monitor-done state)))
       (is (= content (slurp file2)))
       (finally
        (.delete tmpfile1)
        (.delete tmpfile2))))))

(deftest sftp-session-test
  (with-default-identity (private-key-path)
    (with-ssh-agent []
      (let [session (session "localhost" :username (username)
                             :strict-host-key-checking :no)]
        (with-connection session
          (let [channel (ssh-sftp session)]
            (with-connection channel
              (test-sftp-with channel)))
          (test-sftp-transient-with session))))))

(deftest sftp-hostnametest
  (with-default-identity (private-key-path)
    (with-default-session-options {:strict-host-key-checking :no}
      (test-sftp-transient-with "localhost" :username (username)))))

(defn test-scp-to-with
  [session]
  (let [tmpfile1 (java.io.File/createTempFile "clj-ssh" "test")
        tmpfile2 (java.io.File/createTempFile "clj-ssh" "test")
        file1 (.getPath tmpfile1)
        file2 (.getPath tmpfile2)
        content "content"
        content2 "content2"]
    (try
      (.setWritable tmpfile1 true false)
      (.setWritable tmpfile2 true false)
      (io/copy content tmpfile1)
      (scp-to session file1 file2)
      (is (= content (slurp file2)) "scp-to should copy content")
      (io/copy content2 tmpfile1)
      (scp-to "localhost" file1 file2
              :cipher-none true
              :username (username)
              :strict-host-key-checking :no)
      (is (= content2 (slurp file2))
          "scp-to with implicit session should copy content")
      (finally
       (.delete tmpfile1)
       (.delete tmpfile2)))))

(defn test-scp-from-with
  [session]
  (let [tmpfile1 (java.io.File/createTempFile "clj-ssh" "test")
        tmpfile2 (java.io.File/createTempFile "clj-ssh" "test")
        file1 (.getPath tmpfile1)
        file2 (.getPath tmpfile2)
        content "content"
        content2 "content2"]
    (try
      (.setWritable tmpfile1 true false)
      (.setWritable tmpfile2 true false)
      (io/copy content tmpfile1)
      (scp-from session file1 file2)
      (is (= content (slurp file2))
          "scp-from should copy content")
      (io/copy content2 tmpfile1)
      (scp-from "localhost" file1 file2
                :cipher-none true
                :username (username)
                :strict-host-key-checking :no)
      (is (= content2 (slurp file2))
          "scp-from with implicit session should copy content")
      (finally
       (.delete tmpfile1)
       (.delete tmpfile2)))))

(deftest scp-test
  (with-default-identity (private-key-path)
    (with-ssh-agent []
      (let [session (session "localhost" :username (username)
                             :strict-host-key-checking :no)]
        (with-connection session
          (test-scp-to-with session)
          (test-scp-from-with session))))))

(deftest generate-keypair-test
  (with-ssh-agent []
    (let [[priv pub] (generate-keypair :rsa 1024 "hello")]
      (add-identity *ssh-agent* "name" priv pub (.getBytes "hello")))))

(defn port-reachable?
  ([ip port timeout]
     (let [socket (doto (java.net.Socket.)
                    (.setReuseAddress false)
                    (.setSoLinger false 1)
                    (.setSoTimeout timeout))]
       (try
         (.connect socket (java.net.InetSocketAddress. ip port))
         true
         (catch java.io.IOException _)
         (finally
           (try (.close socket) (catch java.io.IOException _))))))
  ([ip port]
     (port-reachable? ip port 2000))
  ([port]
     (port-reachable? "localhost" port)))

(deftest forward-local-port-test
  (testing "minimal test"
    (with-ssh-agent [false]
      (add-identity (private-key-path))
      (let [session (session "localhost" :username (username)
                             :strict-host-key-checking :no)]
        (is (instance? com.jcraft.jsch.Session session))
        (is (not (connected? session)))
        (is (not (port-reachable? 2222)))
        (connect session)
        (is (connected? session))
        (forward-local-port session 2222 22)
        (is (port-reachable? 2222))
        (unforward-local-port session 2222)
        (forward-local-port session 2222 22 "localhost")
        (unforward-local-port session 2222)
        (with-local-port-forward [session 2222 22]
          (is (port-reachable? 2222)))
        (with-local-port-forward [session 2222 22 "localhost"]
          (is (port-reachable? 2222)))))))

(deftest forward-remote-port-test
  (testing "minimal test"
    (with-ssh-agent [false]
      (add-identity (private-key-path))
      (let [session (session "localhost" :username (username)
                             :strict-host-key-checking :no)]
        (is (instance? com.jcraft.jsch.Session session))
        (is (not (connected? session)))
        (is (not (port-reachable? 2222)))
        (connect session)
        (is (connected? session))
        (forward-remote-port session 2222 22)
        (is (port-reachable? 2222))
        (unforward-remote-port session 2222)
        (forward-remote-port session 2222 22 "localhost")
        (unforward-remote-port session 2222)
        (with-remote-port-forward [session 2222 22]
          (is (port-reachable? 2222)))
        (with-remote-port-forward [session 2222 22 "localhost"]
          (is (port-reachable? 2222)))))))
