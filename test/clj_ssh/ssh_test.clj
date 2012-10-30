(ns clj-ssh.ssh-test
  (:use
   clojure.test
   clj-ssh.ssh
   clj-ssh.test-keys
   [clj-ssh.test-utils
    :only [quiet-ssh-logging sftp-monitor sftp-monitor-done]])
  (:require [clojure.java.io :as io])
  (:import com.jcraft.jsch.JSch))

(use-fixtures :once quiet-ssh-logging)

(deftest file-path-test
  (is (= "abc" (#'clj-ssh.ssh/file-path "abc")))
  (is (= "abc" (#'clj-ssh.ssh/file-path (java.io.File. "abc")))))

(deftest camelize-test
  (is (= "StrictHostKeyChecking"
         (#'clj-ssh.ssh/camelize "strict-host-key-checking"))))

(deftest ssh-agent?-test
  (is (ssh-agent? (JSch.)))
  (is (not (ssh-agent? "i'm not an ssh-agent"))))

(deftest create-ssh-agent-test
  (is (ssh-agent? (ssh-agent {})))
  (is (ssh-agent? (ssh-agent {:use-system-ssh-agent false}))))

(deftest make-identity-test
  (let [agent (ssh-agent {:use-system-ssh-agent false})]
    (let [path (private-key-path)
          identity (make-identity agent path (str path ".pub"))]
      (is (instance? com.jcraft.jsch.Identity identity))
      (is (not (.isEncrypted identity)))
      (add-identity agent {:identity identity})
      (is (= 1 (count (.getIdentityNames agent)))))
    (let [path (encrypted-private-key-path)
          identity (make-identity agent path (str path ".pub"))]
      (is (instance? com.jcraft.jsch.Identity identity))
      (is (.isEncrypted identity))
      (add-identity agent {:identity identity :name "clj-ssh"})
      (is (= 2 (count (.getIdentityNames agent)))))))

(deftest add-identity-test
  (let [key (private-key-path)]
    (let [agent (ssh-agent {:use-system-ssh-agent false})]
      (add-identity agent {:private-key-path key})
      (is (= 1 (count (.getIdentityNames agent))))
      (add-identity agent {:private-key-path key})))
  (testing "passing byte arrays"
    (let [agent (ssh-agent {:use-system-ssh-agent false})]
      (add-identity
       agent
       {:name "name"
        :private-key (.getBytes (slurp (private-key-path)))
        :public-key (.getBytes (slurp (public-key-path)))})
      (is (= 1 (count (.getIdentityNames agent))))
      (is (= "name" (first (.getIdentityNames agent))))))
  (testing "ssh-agent"
    (let [agent (ssh-agent {})]
      (let [n (count (.getIdentityNames agent))
            test-key-comment "key for test clj-ssh"
            has-key (some #(= (private-key-path) %) (.getIdentityNames agent))]
        (add-identity
         agent
         {:private-key-path (private-key-path)
          :public-key-path (public-key-path)})
        (is (or has-key (= (inc n) (count (.getIdentityNames agent)))))
        (is (some #(= (private-key-path) %) (.getIdentityNames agent)))))))

(deftest has-identity?-test
  (let [key (private-key-path)
        pub-key (public-key-path)]
    (testing "private-key-path only"
      (let [agent (ssh-agent {:use-system-ssh-agent false})]
        (is (not (has-identity? agent key)))
        (is (zero? (count (.getIdentityNames agent))))
        (add-identity agent {:private-key-path key})
        (is (= 1 (count (.getIdentityNames agent))))
        (is (has-identity? agent key))))
    (testing "private-key-path and public-key-path"
      (let [agent (ssh-agent {:use-system-ssh-agent false})]
        (is (not (has-identity? agent key)))
        (is (zero? (count (.getIdentityNames agent))))
        (add-identity agent {:private-key-path key
                             :public-key-path pub-key})
        (is (= 1 (count (.getIdentityNames agent))))
        (is (has-identity? agent key))))))

(deftest session-impl-test
  (let [agent (ssh-agent {:use-system-ssh-agent false})]
    (let [session
          (#'clj-ssh.ssh/session-impl agent "somehost" (username) 22 nil {})]
      (is (instance? com.jcraft.jsch.Session session))
      (is (not (connected? session))))
    (let [session
          (#'clj-ssh.ssh/session-impl agent "localhost" (username) 22 nil
                                      {:strict-host-key-checking :no})]
      (is (instance? com.jcraft.jsch.Session session))
      (is (not (connected? session)))
      (is (= "no" (.getConfig session "StrictHostKeyChecking"))))))

(deftest session-test
  (let [agent (ssh-agent {:use-system-ssh-agent false})]
    (let [session (session agent "localhost" {:username (username) :port 22})]
      (is (instance? com.jcraft.jsch.Session session))
      (is (not (connected? session)))))
  (let [agent (ssh-agent {:use-system-ssh-agent false})]
    (let [session (session agent "localhost" {:username (username)})]
      (is (instance? com.jcraft.jsch.Session session))
      (is (not (connected? session)))))
  (let [agent (ssh-agent {:use-system-ssh-agent false})]
    (let [session (session agent "localhost" {:username (username)})]
      (is (instance? com.jcraft.jsch.Session session))
      (is (not (connected? session))))))

(deftest session-connect-test
  (testing "internal agent"
    (let [agent (ssh-agent {:use-system-ssh-agent false})]
      (add-identity agent {:private-key-path (private-key-path)})
      (let [session (session
                     agent
                     "localhost"
                     {:username (username) :strict-host-key-checking :no})]
        (is (instance? com.jcraft.jsch.Session session))
        (is (not (connected? session)))
        (connect session)
        (is (connected? session))
        (disconnect session)
        (is (not (connected? session))))
      (let [session (session
                     agent
                     "localhost"
                     {:username (username) :strict-host-key-checking :no})]
        (with-connection session
          (is (connected? session)))
        (is (not (connected? session)))))
    (testing "key with passphrase"
      (let [agent (ssh-agent {:use-system-ssh-agent false})]
        (add-identity-with-keychain
          agent
          {:private-key-path (encrypted-private-key-path)
           :passphrase "clj-ssh"})
        (let [session (session
                       agent
                       "localhost"
                       {:username (username)
                        :strict-host-key-checking :no})]
          (is (instance? com.jcraft.jsch.Session session))
          (is (not (connected? session)))
          (connect session)
          (is (connected? session))
          (disconnect session)
          (is (not (connected? session))))
        (let [session (session
                       agent
                       "localhost"
                       {:username (username)
                        :strict-host-key-checking :no})]
          (with-connection session
            (is (connected? session)))
          (is (not (connected? session)))))))
  (testing "system ssh-agent"
    (let [agent (ssh-agent {})]
      (let [session (session
                     agent
                     "localhost"
                     {:username (username)
                      :strict-host-key-checking :no})]
        (is (instance? com.jcraft.jsch.Session session))
        (is (not (connected? session)))
        (connect session)
        (is (connected? session))
        (disconnect session)
        (is (not (connected? session))))
      (let [session (session
                     agent
                     "localhost"
                     {:username (username)
                      :strict-host-key-checking :no})]
        (with-connection session
          (is (connected? session)))
        (is (not (connected? session)))))))

(deftest open-shell-channel-test
  (let [agent (ssh-agent {:use-system-ssh-agent false})]
    (add-identity agent {:private-key-path (private-key-path)})
    (let [session (session
                   agent
                   "localhost"
                   {:username (username) :strict-host-key-checking :no})]
      (with-connection session
        (let [shell (open-channel session :shell)
              os (java.io.ByteArrayOutputStream.)]
          (.setInputStream
           shell
           (java.io.ByteArrayInputStream. (.getBytes "ls /;exit 0;\n"))
           false)
          (.setOutputStream shell os)
          (with-channel-connection shell
            (while (connected-channel? shell)
                   (Thread/sleep 1000))
            (is (.contains (str os) "bin"))))))))

(deftest ssh-shell-test
  (let [agent (ssh-agent {:use-system-ssh-agent false})]
    (add-identity agent {:private-key-path (private-key-path)})
    (let [session (session
                   agent
                   "localhost"
                   {:username (username) :strict-host-key-checking :no})]
      (with-connection session
        (let [{:keys [exit out]} (ssh-shell session "echo hello" "UTF-8" {})]
          (is (zero? exit))
          (is (.contains out "hello")))
        (let [{:keys [exit out]} (ssh-shell session "echo hello" :bytes {})]
          (is (zero? exit))
          (is (.contains (String. out) "hello")))
        (let [{:keys [exit out]} (ssh-shell
                                  session "echo hello;exit 1" "UTF-8" {})]
          (is (= 1 exit))
          (is (.contains out "hello")))
        (let [{:keys [channel out-stream]} (ssh-shell
                                     session "echo hello;exit 1" :stream {})]
          (while (connected-channel? channel) (Thread/sleep 100))
          (is (= 1 (.getExitStatus channel)))
          (is (pos? (.available out-stream)))
          (let [bytes (byte-array 1024)
                n (.read out-stream bytes 0 1024)]
            (is (.contains (String. bytes 0 n)  "hello"))))
        (let [{:keys [exit out]} (ssh-shell
                                  session "exit $(tty -s)" "UTF-8" {:pty true})]
          (is (zero? exit)))
        (let [{:keys [exit out]} (ssh-shell
                                  session "exit $(tty -s)" "UTF-8" {:pty nil})]
          (is (= 1 exit)))
        (let [{:keys [exit out]} (ssh-shell
                                  session "ssh-add -l" "UTF-8" {})]
          (is (pos? exit)))
        (let [{:keys [exit out]} (ssh-shell
                                  session "ssh-add -l" "UTF-8"
                                  {:agent-forwarding false})]
          (is (pos? exit)))
        (let [{:keys [exit out]} (ssh-shell
                                  session "ssh-add -l" "UTF-8"
                                  {:agent-forwarding true})]
          (is (re-find #"RSA" out))
          (is (zero? exit)))))))

(deftest ssh-exec-test
  (let [agent (ssh-agent {:use-system-ssh-agent false})]
    (add-identity agent {:private-key-path (private-key-path)})
    (let [session (session
                   agent
                   "localhost"
                   {:username (username)
                    :strict-host-key-checking :no})]
      (with-connection session
        (let [{:keys [exit out err]}
              (ssh-exec session "/bin/bash -c 'ls /'" nil "UTF-8" {})]
          (is (zero? exit))
          (is (.contains out "bin"))
          (is (= "" err)))
        (let [{:keys [exit out err]}
              (ssh-exec session "/bin/bash -c 'lsxxxxx /'" nil "UTF-8" {})]
          (is (pos? exit))
          (is (= "" out))
          (is (.contains err "command not found")))
        (let [{:keys [channel out-stream err-stream]}
              (ssh-exec
               session "/bin/bash -c 'ls / && lsxxxxx /'" nil :stream {})]
          (while (connected-channel? channel) (Thread/sleep 100))
          (is (not= 0 (.getExitStatus channel)))
          (is (pos? (.available out-stream)))
          (is (pos? (.available err-stream)))
          (let [out-bytes (byte-array 1024)
                out-n (.read out-stream out-bytes 0 1024)
                err-bytes (byte-array 1024)
                err-n (.read err-stream err-bytes 0 1024)]
            (is (.contains (String. out-bytes 0 out-n) "bin"))
            (is (.contains
                 (String. err-bytes 0 err-n)
                 "command not found"))))))))

(deftest ssh-test
  (let [agent (ssh-agent {:use-system-ssh-agent false})]
    (add-identity agent {:private-key-path (private-key-path)})
    (let [session (session
                   agent
                   "localhost"
                   {:username (username)
                    :strict-host-key-checking :no})]
      (with-connection session
        (let [{:keys [exit out]} (ssh session {:in "echo hello"})]
          (is (zero? exit))
          (is (.contains out "hello")))
        (let [{:keys [exit out err]} (ssh session {:cmd "/bin/bash -c 'ls /'"})]
          (is (zero? exit))
          (is (.contains out "bin"))
          (is (= "" err)))
        (let [{:keys [exit out]} (ssh
                                  session
                                  {:in "echo hello" :username (username)})]
          (is (zero? exit))
          (is (.contains out "hello")))
        (let [{:keys [exit out err]}
              (ssh session {:cmd "/bin/bash -c 'ls /'" :username (username)})]
          (is (zero? exit))
          (is (.contains out "bin"))
          (is (= "" err)))
        (let [{:keys [exit out]}
              (ssh session {:in "tty -s" :pty true :username (username)})]
          (is (zero? exit)))
        (let [{:keys [exit out]}
              (ssh session {:in "tty -s" :pty false :username (username)})]
          (is (= 1 exit)))
        (let [{:keys [exit out]}
              (ssh session {:in "ssh-add -l" :agent-forwarding true
                            :username (username)})]
          (is (zero? exit))))))
  (let [agent (ssh-agent {:use-system-ssh-agent :false})]
    (add-identity agent {:private-key-path (private-key-path)})
    (let [session (session agent "localhost" {:username (username)
                                              :strict-host-key-checking :no})]
      (with-connection session
        (let [{:keys [exit out]}
              (ssh session {:in "echo hello" :username (username)})]
          (is (zero? exit))
          (is (.contains out "hello")))
        (let [{:keys [exit out]}
              (ssh session {:in "echo hello" :username (username)})]
          (is (zero? exit))
          (is (.contains out "hello")))
        (let [{:keys [exit out err]}
              (ssh session {:cmd "/bin/bash -c 'ls /'" :username (username)})]
          (is (zero? exit))
          (is (.contains out "bin"))
          (is (= "" err)))
        (let [{:keys [exit out err]}
              (ssh session {:cmd "/bin/bash -c 'ls /'" :return-map true
                            :username (username)})]
          (is (zero? exit))
          (is (.contains out "bin"))
          (is (= "" err)))))))

(deftest ssh-sftp-cmd-test
  (let [agent (ssh-agent {:use-system-ssh-agent false})]
    (add-identity agent {:private-key-path (private-key-path)})
    (let [session (session agent "localhost" {:username (username)
                                              :strict-host-key-checking :no})]
      (with-connection session
        (let [channel (ssh-sftp session)
              dir (ssh-sftp-cmd channel :ls ["/"] {})]
          (ssh-sftp-cmd channel :cd ["/"] {})
          (is (= "/" (ssh-sftp-cmd channel :pwd [] {})))
          ;; value equality comparison on lsentry is borked
          (is (= (map str dir)
                 (map str (ssh-sftp-cmd channel :ls [] {})))))))))

(defn test-sftp-with [channel]
  (let [dir (sftp channel {} :ls "/")]
    (sftp channel {} :cd "/")
    (is (= "/" (sftp channel {} :pwd)))
    ;; value equality comparison on lsentry is borked
    (is (= (map str dir)
           (map str (sftp channel {} :ls))))
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
       (sftp channel {} :put file1 file2)
       (is (= content (slurp file2)))
       (io/copy content2 tmpfile2)
       (sftp channel {} :get file2 file1)
       (is (= content2 (slurp file1)))
       (sftp
        channel {}
        :put (java.io.ByteArrayInputStream. (.getBytes content)) file1)
       (is (= content (slurp file1)))
       (let [[monitor state] (sftp-monitor)]
         (sftp channel {:with-monitor monitor}
               :put (java.io.ByteArrayInputStream. (.getBytes content)) file2)
         (is (sftp-monitor-done state)))
       (is (= content (slurp file2)))
       (finally
        (.delete tmpfile1)
        (.delete tmpfile2))))))

(defn test-sftp-transient-with [channel {:as options}]
  (let [dir (sftp channel options :ls "/")]
    (sftp channel options :cd "/")
    (is (not= "/" (sftp channel options :pwd)))
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
        (sftp channel options :put file1 file2)
        (is (= content (slurp file2)))
        (io/copy content2 tmpfile2)
        (sftp channel options :get file2 file1)
        (is (= content2 (slurp file1)))
        (sftp channel options
               :put (java.io.ByteArrayInputStream. (.getBytes content))
               file1)
        (is (= content (slurp file1)))
        (let [[monitor state] (sftp-monitor)]
          (sftp channel (assoc options :with-monitor monitor)
                :put (java.io.ByteArrayInputStream. (.getBytes content))
                file2)
          (is (sftp-monitor-done state)))
        (is (= content (slurp file2)))
        (finally
          (.delete tmpfile1)
          (.delete tmpfile2))))))

(deftest sftp-session-test
  (let [agent (ssh-agent {:use-system-ssh-agent false})]
    (add-identity agent {:private-key-path (private-key-path)})
    (let [session (session agent "localhost" {:username (username)
                                              :strict-host-key-checking :no})]
      (with-connection session
        (let [channel (ssh-sftp session)]
          (with-channel-connection channel
            (test-sftp-with channel)))
        (test-sftp-transient-with session {})))))

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
      (scp-to session file1 file2)
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
      (scp-from session file1 file2
                :cipher-none true
                :username (username)
                :strict-host-key-checking :no)
      (is (= content2 (slurp file2))
          "scp-from with implicit session should copy content")
      (finally
        (.delete tmpfile1)
        (.delete tmpfile2)))))

(deftest scp-test
  (let [agent (ssh-agent {:use-system-ssh-agent false})]
    (add-identity agent {:private-key-path (private-key-path)})
    (let [session (session
                   agent
                   "localhost"
                   {:username (username) :strict-host-key-checking :no})]
      (with-connection session
        (test-scp-to-with session)
        (test-scp-from-with session)))))

(deftest generate-keypair-test
  (let [agent (ssh-agent {:use-system-ssh-agent false})]
    (let [[priv pub] (generate-keypair agent :rsa 1024 "hello")]
      (add-identity agent {:name "name"
                           :private-key priv
                           :public-key pub
                           :passphrase (.getBytes "hello")}))))

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
    (let [agent (ssh-agent {:use-system-ssh-agent false})]
      (add-identity agent {:private-key-path (private-key-path)})
      (let [session (session
                     agent
                     "localhost"
                     {:username (username)
                      :strict-host-key-checking :no})]
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
    (let [agent (ssh-agent {:use-system-ssh-agent false})]
      (is (zero? (count (.getIdentityNames agent))))
      (add-identity agent {:private-key-path (private-key-path)
                           :public-key-path (public-key-path)})
      (is (pos? (count (.getIdentityNames agent))))
      (let [session (session
                     agent
                     "localhost"
                     {:username (username)
                      :strict-host-key-checking :no})]
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
