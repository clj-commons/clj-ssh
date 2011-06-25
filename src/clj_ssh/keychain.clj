(ns clj-ssh.keychain
  "Primitive keychain support for clj-ssh.  Only implemented on OSX at the
   moment."
  (:require
   [clojure.tools.logging :as logging]
   [clojure.java.shell :as shell]))

(defn ask-passphrase [path]
  (when-let [console (. System console)]
    (print "Passphrase for" path ": ")
    (.readPassword console)))

(defmulti keychain-passphrase "Obtain password for path"
  (fn [system path] system))

(defmethod keychain-passphrase :default
  [system path]
  (logging/warn "Passphrase required, but no keychain implemented.")
  (ask-passphrase path))

(defmethod keychain-passphrase "Mac OS X"
  [system path]
  (let [result (shell/sh
                "/usr/bin/security" "find-generic-password" "-a"
                (format "%s" path)
                "-g")]
    (when (zero? (result :exit))
      (second (re-find #"password: \"(.*)\"" (result :err))))))

(defn passphrase
  "Obtain a passphrase for the given key path"
  [path]
  (keychain-passphrase (System/getProperty "os.name") path))
