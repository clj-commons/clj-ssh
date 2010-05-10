(ns #^{:author "Hugo Duncan"}
  clj-ssh.ssh
  "SSH in clojure.  Uses jsch.  Provides a ssh function that tries to look similar
to clojure.contrib.shell/sh.

## Usage

The top level namespace is `clj-ssh.ssh`

    (use 'clj-ssh.ssh)

There is a simple `ssh` function, which by default, will try and use a id_rsa
key in your $HOME/.ssh directory.

    (ssh hostname cmd)

Strict host key checking can be turned off.

    (default-session-options {:strict-host-key-checking :no})

More advance usage is possible.

    (with-ssh-agent []
      (add-identity path-to-private-key)
      (let [session (session hostname :strict-host-key-checking :no)]
        (with-connection session
          (let [result (ssh session :in commands-string :result-map true)]
            (println (result :out)))
          (let [result (ssh session some-cmd-string)]
            (println (second result))))))

## Installation

Via maven and the clojars (http://clojars.org), or
Leiningen (http://github.com/technomancy/leiningen).

## License

Licensed under EPL (http://www.eclipse.org/legal/epl-v10.html)"
  (:use
   [clojure.contrib.def :only [defvar]])
  (:require
   [clojure.contrib.logging :as logging])
  (:import [com.jcraft.jsch
            JSch Session Channel ChannelShell ChannelExec ChannelSftp
            Identity IdentityFile Logger]))

(defvar *ssh-agent* nil "SSH agent used to manage identities.")
(defvar *default-session-options* {} "Default SSH options")

;; working towards clojure 1.1/1.2 compat
(try
  (use '[clojure.contrib.reflect :only [call-method]])
  (use '[clojure.contrib.io :only [file]])
  (catch Exception e
    (use '[clojure.contrib.java-utils
           :only [file wall-hack-method]
           :rename {wall-hack-method call-method}])))

(defmacro when-feature
  [feature-name & body]
  (if (find-var (symbol "clojure.core" (name feature-name)))
    `(do ~@body)))

;; Enable java logging of jsch when in clojure 1.2
(when-feature deftype
 (def ssh-log-levels
      {com.jcraft.jsch.Logger/DEBUG :debug
       com.jcraft.jsch.Logger/INFO  :info
       com.jcraft.jsch.Logger/WARN  :warn
       com.jcraft.jsch.Logger/ERROR :error
       com.jcraft.jsch.Logger/FATAL :fatal})
 (deftype SshLogger
   [log-level]
   com.jcraft.jsch.Logger
   (isEnabled
    [_ level]
    (>= level log-level))
   (log
    [_ level message]
    (logging/log (ssh-log-levels level) message nil "clj-ssh.ssh")))

 (JSch/setLogger (SshLogger. com.jcraft.jsch.Logger/DEBUG)))

(defmacro with-default-session-options
  "Set the default session options"
  [options & body]
  `(binding [*default-session-options* ~options]
     ~@body))

(defn default-session-options
  "Set the default session options"
  [options]
  (alter-var-root #'*default-session-options* #(identity %2) options))

(defn- #^String file-path [string-or-file]
  (if (string? string-or-file)
    string-or-file
    (.getPath #^java.io.File string-or-file)))

(defn ssh-agent?
  "Predicate to test for an ssh-agent."
  [object] (instance? JSch object))

(defn- default-user []
  (. System getProperty "user.name"))

(def *default-identity*
     (.getPath (file (. System getProperty "user.home") ".ssh" "id_rsa")))

(defmacro with-default-identity
  "Bind the default identity."
  [path & body]
  `(binding [*default-identity* ~path]
     ~@body))

(defn default-identity
  []
  (if-let [id-file (java.io.File. *default-identity*)]
    (if (.canRead id-file)
      id-file)))

(defn make-identity
  "Create a JSch identity.  This can be used to check whether the key is
   encrypted."
  ([private-key-path public-key-path]
     (make-identity *ssh-agent* private-key-path public-key-path))
  ([#^JSch agent #^String private-key-path #^String public-key-path]
     (call-method
      com.jcraft.jsch.IdentityFile 'newInstance [String String JSch]
      nil private-key-path public-key-path agent)))

(defn add-identity
  "Add an identity to the agent."
  ([]
     (add-identity *ssh-agent* (default-identity) nil))
  ([private-key]
     (add-identity *ssh-agent* private-key nil))
  ([#^JSch agent private-key]
     (if (ssh-agent? agent)
       (add-identity agent private-key nil)
       (add-identity *ssh-agent* agent private-key)))
  ([#^JSch agent private-key #^String passphrase]
     (.addIdentity
      agent
      (if (instance? Identity private-key)
        private-key
        (file-path private-key))
      (and passphrase (.getBytes passphrase)))))

(defn has-identity?
  "Check if the given identity is present."
  ([name] (has-identity? *ssh-agent* name))
  ([agent name] (some #(= name %) (.getIdentityNames *ssh-agent*))))

(defn create-ssh-agent
  "Create an ssh-agent. By default try and add the current user's id_rsa key."
  ([] (create-ssh-agent true))
  ([add-default-identity?]
     (if (or (string? add-default-identity?)
             (instance? java.io.File add-default-identity?))
       (create-ssh-agent add-default-identity? nil)
       (let [agent (JSch.)]
         (when add-default-identity?
           (if-let [default-id (default-identity)]
             (add-identity agent default-id)))
         agent)))
  ([private-key passphrase?]
     (let [agent (JSch.)]
       (if passphrase?
         (add-identity agent private-key passphrase?)
         (add-identity agent private-key))
       agent)))

(defmacro with-ssh-agent
  "Bind an ssh-agent for use as identity manager.
The argument vector can be empty, in which case a new agent is created.  If passed a String or File, then this is passed to the new agent as an identity to be added.
An existing agent instance can alternatively be passed."
  [[& agent] & body]
  `(binding [*ssh-agent*
             ~(if (seq agent)
                `(let [arg# ~(first agent)]
                   (if (ssh-agent? arg#)
                     arg#
                     (create-ssh-agent ~@agent)))
                `(create-ssh-agent))]
     ~@body))

(defn #^String capitalize
  "Converts first character of the string to upper-case."
  [#^String s]
  (if (< (count s) 2)
    (.toUpperCase s)
    (str (.toUpperCase #^String (subs s 0 1))
         (subs s 1))))

(defn- camelize [#^String a]
  (apply str (map capitalize (.split a "-"))))

(defn- #^String as-string [arg]
  (cond
   (symbol? arg) (name arg)
   (keyword? arg) (name arg)
   :else (str arg)))

(defn- session-impl
  [#^JSch agent hostname username port #^String password options]
  (let [session (.getSession
                 agent
                 (or username (default-user))
                 hostname
                 (or port 22))]
    (when password
      (.setPassword session password))
    (doseq [option options]
      (.setConfig
       session
       (camelize (as-string (first option)))
       (as-string (second option))))
    session))

(defn session
  "Start a SSH session.
Requires hostname.  you can also pass values for :username, :password and :port
keys.  All other option key pairs will be passed as SSH config options."
  [agent? & options]
  (let [agent-provided? (ssh-agent? agent?)
        agent (if agent-provided? agent? *ssh-agent*)
        hostname (if agent-provided? (first options) agent?)
        options (if agent-provided? (rest options) options)
        options (apply hash-map options)]
    (session-impl agent hostname
                  (or (options :username) (. System getProperty "user.name"))
                  (options :port)
                  (options :password)
                  (dissoc options :username :port :password))))

(defn connect
  "Connect a session."
  ([session]
     (.connect session))
  ([session timeout]
     (.connect session timeout)))

(defn disconnect
  "Disconnect a session."
  ([session]
     (.disconnect session)))

(defn connected?
  "Predicate used to test for a connected session."
  ([session]
     (.isConnected session)))

(defmacro with-connection
  "Creates a context in which the session is connected. Ensures the session is
  disconnected on exit."
  [session & body]
  `(let [session# ~session]
     (try
      (when-not (connected? session#)
        (connect session#))
      ~@body
      (finally (disconnect session#)))))

(defn open-channel
  "Open a channel of the specified type in the session."
  [#^Session session session-type]
  (.openChannel session (name session-type)))

(defn sftp-channel
  "Open a SFTP channel in the session."
  [#^Session session]
  (open-channel session :sftp))

(defn exec-channel
  "Open an Exec channel in the session."
  [#^Session session]
  (open-channel session :exec))

(defn shell-channel
  "Open a Shell channel in the session."
  [#^Session session]
  (open-channel session :shell))


(defn ssh-shell
  "Run a ssh-shell."
  [#^Session session in out opts]
  (let [#^ChannelShell shell (open-channel session :shell)
        out-stream (java.io.ByteArrayOutputStream.)]
    (doto shell
      (.setInputStream
       (if (string? in)
         (java.io.ByteArrayInputStream. (.getBytes (str in ";exit $?;\n")))
         in)
       false)
      (.setOutputStream out-stream))
    (when (contains? opts :pty)
      (call-method com.jcraft.jsch.ChannelSession 'setPty [Boolean/TYPE]
                   shell (boolean (opts :pty))))
    (with-connection shell
      (while (connected? shell)
             (Thread/sleep 100))
      [(.getExitStatus shell)
       (if (= :bytes out)
         (.toByteArray out-stream)
         (.toString out-stream out))])))

(defn ssh-exec
  "Run a command via ssh-exec."
  [#^Session session #^String cmd in out opts]
  (let [#^ChannelExec exec (open-channel session :exec)
        out-stream (java.io.ByteArrayOutputStream.)
        err-stream (java.io.ByteArrayOutputStream.)]
    (doto exec
      (.setInputStream
       (if (string? in)
         (java.io.ByteArrayInputStream. (.getBytes in))
         in)
       false)
      (.setOutputStream out-stream)
      (.setErrStream err-stream)
      (.setCommand cmd))
    (when (contains? opts :pty)
      (call-method com.jcraft.jsch.ChannelSession 'setPty [Boolean/TYPE]
                   exec (boolean (opts :pty))))
    (with-connection exec
      (while (connected? exec)
             (Thread/sleep 100))
      [(.getExitStatus exec)
       (if (= :bytes out)
        (.toByteArray out-stream)
        (.toString out-stream out))
       (if (= :bytes out)
        (.toByteArray err-stream)
        (.toString err-stream out))])))

(defn default-session [host username port password]
  (doto (session-impl
         (or *ssh-agent* (create-ssh-agent))
         host username port password
         *default-session-options*)
    (.connect)))

(defn- parse-args
  "Takes a seq of 'ssh' arguments and returns a map of option keywords
  to option values."
  [args]
  (loop [[arg :as args] args
         opts {:cmd [] :out "UTF-8"}]
    (if-not args
      opts
      (if (keyword? arg)
        (recur (nnext args) (assoc opts arg (second args)))
        (recur (next args) (update-in opts [:cmd] conj arg))))))

(defn ssh
  "Execute commands over ssh.

  ssh host-or-session cmd? & options

cmd specifies a command to exec.  If no cmd is given, a shell is started and input is taken from :in.

Options are

:in         specifies input to the remote shell. A string or a stream.
:out        specify :bytes or a string with an encoding specification.
:return-map when followed by boolean true, sh returns a map of
              :exit => sub-process's exit code
              :out  => sub-process's stdout (as byte[] or String)
              :err  => sub-process's stderr (as byte[] or String)
            when not given or followed by false, ssh returns a vector
            of the remote shell's stdout followed by its stderr
:username   username to use for authentication
:password   password to use for authentication
:port       port to use if no session specified
"
  [session-or-hostname & args]
  (let [opts (parse-args args)
        session-given (instance? com.jcraft.jsch.Session session-or-hostname)
        session (if session-given
                  session-or-hostname
                  (default-session
                   session-or-hostname
                   (opts :username)
                   (opts :port)
                   (opts :password)))]
    (try
     (when-not (connected? session)
       (connect session))
     (if (empty? (:cmd opts))
       (let [result (ssh-shell
                     session
                     (:in opts) (:out opts) (dissoc opts :in :out :cmd))]
         (if (opts :return-map)
           {:exit (first result) :out (second result)}
           result))
       (let [result (ssh-exec
                     session
                     (apply str (interpose " " (:cmd opts)))
                     (:in opts) (:out opts) (dissoc opts :in :out :cmd))]
         (if (opts :return-map)
           {:exit (first result) :out (second result) :err (last result)}
           result)))
     (finally
      (when-not session-given
        (disconnect session))))))

(defn ssh-sftp
  "Obtain a connected ftp channel."
  [#^Session session]
  {:pre (connected? session)}
  (let [channel (open-channel session :sftp)]
    (connect channel)
    channel))

(defmacro memfn-varargs [name]
  `(fn [target# args#]
    (condp = (count args#)
      0 (. target# (~name))
      1 (. target# (~name (first args#)))
      2 (. target# (~name (first args#) (second args#)))
      3 (. target# (~name (first args#) (second args#) (nth args# 2)))
      4 (. target# (~name (first args#) (second args#) (nth args# 2) (nth args# 3)))
      5 (. target# (~name (first args#) (second args#) (nth args# 2) (nth args# 3) (nth args# 4)))
      (throw
       (java.lang.IllegalArgumentException.
        (str
         "too many arguments passed.  Limit 5, passed " (count args#)))))))

(def sftp-modemap { :overwrite ChannelSftp/OVERWRITE
                    :resume ChannelSftp/RESUME
                    :append ChannelSftp/APPEND })

(defn ssh-sftp-cmd
  "Command on a ftp channel."
  [#^ChannelSftp channel cmd args options]
  (condp = cmd
    :ls (.ls channel (or (first args) "."))
    :cd (.cd channel (first args))
    :lcd (.lcd channel (first args))
    :chmod (.chmod channel (first args) (second args))
    :chown (.chown channel (first args) (second args))
    :chgrp (.chgrp channel (first args) (second args))
    :pwd (.pwd channel)
    :lpwd (.lpwd channel)
    :rm (.rm channel (first args))
    :rmdir (.rmdir channel (first args))
    :rmdir (.mkdir channel (first args))
    :stat (.stat channel (first args))
    :lstat (.lstat channel (first args))
    :rename (.rename channel (first args) (second args))
    :symlink (.symlink channel (first args) (second args))
    :readlink (.readlink channel (first args))
    :realpath (.realpath channel (first args) (second args))
    :get-home (.getHome channel)
    :get-server-version (.getServerVersion channel)
    :get-extension (.getExtension channel (first args))
    :get (let [args (if (options :with-monitor)
                      (conj args (options :with-monitor))
                      args)
               args (if (options :mode)
                      (conj args (sftp-modemap (options :mode)))
                      args)]
           ((memfn-varargs get) channel args))
    :put (let [args (if (options :with-monitor)
                      (conj args (options :with-monitor))
                      args)
               args (if (options :mode)
                      (conj args (sftp-modemap (options :mode)))
                      args)]
           ((memfn-varargs put) channel args))
    (throw (java.lang.IllegalArgumentException.
            (str "Unknown SFTP command " cmd)))))

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
"
  [session-or-hostname cmd & args]
  (let [opts (parse-args args)
        channel-given (instance? com.jcraft.jsch.ChannelSftp session-or-hostname)
        session-given (instance? com.jcraft.jsch.Session session-or-hostname)
        session (if session-given
                  session-or-hostname
                  (if channel-given
                    nil
                    (default-session
                      session-or-hostname
                      (opts :username)
                      (opts :port)
                      (opts :password))))
        channel (if channel-given
                  session-or-hostname
                  (ssh-sftp session))]
    (try
     (when (and session (not (connected? session)))
       (connect session))
     (let [result (ssh-sftp-cmd channel cmd (opts :cmd) (dissoc opts :cmd))]
       (if (opts :return-map)
         {:exit (first result) :out (second result)}
         result))
     (finally
      (when-not channel-given
        (disconnect channel))
      (when-not (or session-given channel-given)
        (disconnect session))))))
