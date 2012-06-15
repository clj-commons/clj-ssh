(ns clj-ssh.cli
  "Provides a REPL friendly interface for clj-ssh.

There is a simple `ssh` function, which by default, will use the system
ssh-agent.

    (ssh \"hostname\" \"ls\")

Strict host key checking can be turned off.

    (default-session-options {:strict-host-key-checking :no})

By default, your current username is used.  If your key has a passphrase, and
you are on OSX, then you should be asked for access to your keychain.  If you
are on any other OS without a ssh-agent, you will need to explicitly add your
key to the clj-ssh's ssh-agent with the appropriate add-identity call.

SFTP is supported:

    (sftp \"hostname\" :put \"/from/this/path\" \"to/this/path\")

Note that any sftp commands that change the state of the sftp session (such as
cd) do not work with the simplified interface, as a new session is created each
time."
  (:require
   [clj-ssh.ssh :as ssh]
   [clojure.string :as string]))

;;; Agent
(def ^{:doc "SSH agent used to manage identities." :dynamic true}
  *ssh-agent* (ssh/ssh-agent {}))

(defmacro with-ssh-agent
  "Bind an ssh-agent for use as identity manager. An existing agent instance is
  passed as the first argument."
  [agent & body]
  `(binding [*ssh-agent* ~agent] ~@body))

;;; Identities
(defn has-identity?
  "Check if the given identity is present."
  [name] (ssh/has-identity? *ssh-agent* name))

(defn add-identity
  "Add an identity to the agent.

:private-key       A string specifying the private key
:public-key        A string specifying the public key
:private-key-path  A string specifying a path to the private key
:public-key-path   A string specifying a path to the public key
:identity          A jsch Identity object (see make-identity)
:passphrase        A byte array containing the passphrase"
  [& {:keys [agent
             ^String name
             ^String public-key
             ^String private-key
             public-key-path
             private-key-path
             ^Identity identity
             ^bytes passphrase]
      :or {agent *ssh-agent*}
      :as options}]
  {:pre [(map? options)]}
  (ssh/add-identity agent (dissoc options :agent)))

(defn add-identity-with-keychain
  "Add a private key, only if not already known, using the keychain to obtain
   a passphrase if required"
  [& {:keys [agent
             ^String name
             ^String public-key-path
             ^String private-key-path
             ^Identity identity
             ^bytes passphrase]
      :or {agent *ssh-agent*}
      :as options}]
  (ssh/add-identity-with-keychain agent (dissoc options :agent)))

;;; Session
(def
  ^{:doc "Default SSH options"
    :dynamic true}
  *default-session-options* {})

(defmacro with-default-session-options
  "Set the default session options"
  [options & body]
  `(binding [*default-session-options* ~options]
     ~@body))

(defn default-session-options
  "Set the default session options"
  [options]
  {:pre [(map? options)]}
  (alter-var-root #'*default-session-options* #(identity %2) options))

(defn- default-session [agent hostname options]
  (ssh/session agent hostname (merge *default-session-options* options)))

(defn session
  "Start a SSH session.
Requires hostname.  you can also pass values for :username, :password and :port
keys.  All other option key pairs will be passed as SSH config options."
  [hostname & {:keys [port username agent password]
               :or {agent *ssh-agent* port 22}
               :as options}]
  (default-session agent hostname (dissoc options :agent)))

;;; Operations
(defn- parse-args
  "Takes a seq of 'ssh' arguments and returns a map of option keywords
  to option values."
  [args]
  (loop [[arg :as args] args
         opts {:args []}]
    (if-not args
      opts
      (if (keyword? arg)
        (recur (nnext args) (assoc opts arg (second args)))
        (recur (next args) (update-in opts [:args] conj arg))))))

(defn ssh
  "Execute commands over ssh.

Options are:

:cmd        specifies a command to exec.  If no cmd is given, a shell is started
            and input is taken from :in.
:in         specifies input to the remote shell. A string or a stream.
:out        specify :stream to obtain a an [inputstream shell]
            specify :bytes to obtain a byte array
            or specify a string with an encoding specification for a
            result string.  In the case of :stream, the shell can
            be polled for connected status.
:username   username to use for authentication
:password   password to use for authentication
:port       port to use if no session specified

sh returns a map of
              :exit => sub-process's exit code
              :out  => sub-process's stdout (as byte[] or String)
              :err  => sub-process's stderr (as byte[] or String)"
  [hostname & args]
  (let [{:keys [cmd in out username password port ssh-agent args]
         :or {ssh-agent *ssh-agent*}
         :as options} (parse-args args)
        session (default-session ssh-agent hostname options)]
    (ssh/with-connection session
      (ssh/ssh
       session
       (if (seq args)
         (merge {:cmd (string/join " " args)} options)
         options)))))

(defn sftp
  "Execute SFTP commands.

  sftp host-or-session cmd & options

cmd specifies a command to exec.  Valid commands are:
:ls
:put
:get
:chmod
:chown
:chgrp
:cd
:lcd
:pwd
:lpwd
:rm
:rmdir
:stat
:symlink

Options are
:username   username to use for authentication
:password   password to use for authentication
:port       port to use if no session specified
:with-monitor
:modes"
  [hostname cmd & args]
  (let [{:keys [ssh-agent args]
         :or {ssh-agent *ssh-agent*}
         :as options} (parse-args args)
        session (default-session ssh-agent hostname options)]
    (ssh/with-connection session
      (apply ssh/sftp session (dissoc options :args) cmd args))))

;;; Keypairs
(defn generate-keypair
  "Generate a keypair, returned as [private public] byte arrays.
   Valid types are :rsa and :dsa.  key-size is in bytes. passphrase
   can be a string or byte array."
  ([key-type key-size passphrase]
     (ssh/generate-keypair *ssh-agent* key-type key-size passphrase)))
