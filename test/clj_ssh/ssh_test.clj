(ns clj-ssh.ssh-test
  (:use [clj-ssh.ssh] :reload-all)
  (:use [clojure.test]
        [clojure.contrib.duck-streams]))

(defmacro with-private-vars [[ns fns] & tests]
  "Refers private fns from ns and runs tests in context.  From users mailing
list, Alan Dipert and MeikelBrandmeyer."
  `(let ~(reduce #(conj %1 %2 `@(ns-resolve '~ns '~%2)) [] fns)
     ~@tests))

;; This assumes you have this private key
(defn private-key-path
  [] (str (. System getProperty "user.home") "/.ssh/id_rsa"))

(defn username
  [] (. System getProperty "user.name"))

(with-private-vars [clj-ssh.ssh [file-path camelize]]

  (deftest file-path-test
    (is (= "abc" (file-path "abc")))
    (is (= "abc" (file-path (java.io.File. "abc")))))

  (deftest camelize-test
    (is (= "StrictHostKeyChecking" (camelize "strict-host-key-checking")))))


(deftest default-identity-test
  (is (= (private-key-path) (.getPath (default-identity)))))

(deftest default-session-options-test
  (let [old *default-session-options*
        new {:strict-host-key-checking :no}]
    (default-session-options new)
    (is (= new *default-session-options*))
    (default-session-options old)
    (is (= old *default-session-options*))))

(deftest ssh-agent?-test
  (is (ssh-agent? (com.jcraft.jsch.JSch.)))
  (is (not (ssh-agent? "i'm not an ssh-agent"))))

(deftest create-ssh-agent-test
  (is (ssh-agent? (create-ssh-agent)))
  (is (ssh-agent? (create-ssh-agent (private-key-path)))))

(deftest with-ssh-agent-test
  (with-ssh-agent []
    (is (ssh-agent? *ssh-agent*)))
  (with-ssh-agent [(private-key-path)]
    (is (ssh-agent? *ssh-agent*)))
  (let [agent (create-ssh-agent)]
    (with-ssh-agent [agent]
      (is (= *ssh-agent* agent)))))

(deftest add-identity-test
  (let [key (private-key-path)]
    (with-ssh-agent []
      (add-identity key)
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
    (let [session (session *ssh-agent* "localhost" :username (username) :port 22)]
      (is (instance? com.jcraft.jsch.Session session))
      (is (not (connected? session)))))
  (with-ssh-agent []
    (let [session (session "localhost")]
      (is (instance? com.jcraft.jsch.Session session))
      (is (not (connected? session)))))
  (with-ssh-agent []
    (let [session (session *ssh-agent* "localhost" )]
      (is (instance? com.jcraft.jsch.Session session))
      (is (not (connected? session))))))

(deftest session-connect-test
  (with-ssh-agent []
    (add-identity (private-key-path))
    (let [session (session "localhost" :strict-host-key-checking :no)]
      (is (instance? com.jcraft.jsch.Session session))
      (is (not (connected? session)))
      (connect session)
      (is (connected? session))
      (disconnect session)
      (is (not (connected? session))))
    (let [session (session "localhost" :strict-host-key-checking :no)]
      (with-connection session
        (is (connected? session)))
      (is (not (connected? session))))))

(deftest open-shell-channel-test
  (with-ssh-agent []
    (let [session (session "localhost" :strict-host-key-checking :no)]
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
  (with-ssh-agent []
    (let [session (session "localhost" :strict-host-key-checking :no)]
      (with-connection session
        (let [result (ssh-shell session "echo hello" "UTF-8")]
          (is (= 0 (first result)))
          (is (.contains (second result) "hello")))
        (let [result (ssh-shell session "echo hello" :bytes)]
          (is (= 0 (first result)))
          (is (.contains (String. (second result)) "hello")))
        (let [result (ssh-shell session "echo hello;exit 1" "UTF-8")]
          (is (= 1 (first result)))
          (is (.contains (second result) "hello")))))))

(deftest ssh-exec-test
  (with-ssh-agent []
    (let [session (session "localhost" :strict-host-key-checking :no)]
      (with-connection session
        (let [result (ssh-exec session "/bin/bash -c 'ls' '/'" nil "UTF-8")]
          (is (= 0 (first result)))
          (is (.contains (second result) "bin"))
          (is (= "" (last result))))
        (let [result (ssh-exec session "/bin/bash -c 'lsxxxxx' '/'" nil "UTF-8")]
          (is (not (= 0 (first result))))
          (is (= "" (second result)))
          (is (.contains (last result) "command not found")))))))

(deftest ssh-test
  (with-ssh-agent []
    (let [session (session "localhost" :strict-host-key-checking :no)]
      (with-connection session
        (let [result (ssh session :in "echo hello")]
          (is (= 0 (first result)))
          (is (.contains (second result) "hello")))
        (let [result (ssh session "/bin/bash -c 'ls' '/'")]
          (is (= 0 (first result)))
          (is (.contains (second result) "bin"))
          (is (= "" (last result))))))
    (let [result (ssh "localhost" :in "echo hello")]
      (is (= 0 (first result)))
      (is (.contains (second result) "hello")))
    (let [result (ssh "localhost" "/bin/bash -c 'ls' '/'")]
      (is (= 0 (first result)))
      (is (.contains (second result) "bin"))
      (is (= "" (last result)))))
  (with-default-session-options {:strict-host-key-checking :no}
    (let [result (ssh "localhost" :in "echo hello")]
      (is (= 0 (first result)))
      (is (.contains (second result) "hello")))
    (let [result (ssh "localhost" :in "echo hello" :return-map true)]
      (is (= 0 (result :exit)))
      (is (.contains (result :out) "hello")))
    (let [result (ssh "localhost" "/bin/bash -c 'ls' '/'")]
      (is (= 0 (first result)))
      (is (.contains (second result) "bin"))
      (is (= "" (last result))))
    (let [result (ssh "localhost" "/bin/bash -c 'ls' '/'" :return-map true)]
      (is (= 0 (result :exit)))
      (is (.contains (result :out) "bin"))
      (is (= "" (result :err))))))

