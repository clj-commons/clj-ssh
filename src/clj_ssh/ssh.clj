(ns ^{:author "Hugo Duncan"}
  clj-ssh.ssh
  "SSH in clojure.  Uses jsch.  Provides a ssh function that tries to look
similar to clojure.contrib.shell/sh.

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

Via maven and the clojars (http://clojars.org/clj-ssh), or
Leiningen (http://github.com/technomancy/leiningen).

## License

Licensed under EPL (http://www.eclipse.org/legal/epl-v10.html)"
  (:require
   [clj-ssh.keychain :as keychain]
   [clj-ssh.reflect :as reflect]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.logging :as logging])
  (:import [com.jcraft.jsch
            JSch Session Channel ChannelShell ChannelExec ChannelSftp
            Identity IdentityFile Logger KeyPair]))

;; slingshot version compatability
(try
  (use '[slingshot.slingshot :only [throw+]])
  (catch Exception _
    (use '[slingshot.core :only [throw+]])))

(def ^{:doc "SSH agent used to manage identities." :dynamic true}
  *ssh-agent*)

(def
  ^{:doc "Default SSH options"
    :dynamic true}
  *default-session-options* {})

(def ^{:dynamic true}
  ssh-log-levels
  (atom
   {com.jcraft.jsch.Logger/DEBUG :debug
    com.jcraft.jsch.Logger/INFO  :info
    com.jcraft.jsch.Logger/WARN  :warn
    com.jcraft.jsch.Logger/ERROR :error
    com.jcraft.jsch.Logger/FATAL :fatal}))

(deftype SshLogger
   [log-level]
   com.jcraft.jsch.Logger
   (isEnabled
    [_ level]
    (>= level log-level))
   (log
    [_ level message]
    (logging/log "clj-ssh.ssh" (@ssh-log-levels level) nil message)))

 (JSch/setLogger (SshLogger. com.jcraft.jsch.Logger/DEBUG))

(defmacro with-default-session-options
  "Set the default session options"
  [options & body]
  `(binding [*default-session-options* ~options]
     ~@body))

(defn default-session-options
  "Set the default session options"
  [options]
  (alter-var-root #'*default-session-options* #(identity %2) options))

(defn- ^String file-path [string-or-file]
  (if (string? string-or-file)
    string-or-file
    (.getPath ^java.io.File string-or-file)))

(defn ssh-agent?
  "Predicate to test for an ssh-agent."
  [object] (instance? JSch object))

(defn- default-user []
  (. System getProperty "user.name"))

(def ^{:dynamic true} ^String *default-identity*
     (.getPath (io/file (. System getProperty "user.home") ".ssh" "id_rsa")))

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

(defn has-identity?
  "Check if the given identity is present."
  ([name] (has-identity? *ssh-agent* name))
  ([^JSch agent name] (some #(= name %) (.getIdentityNames agent))))

(defn ^Identity make-identity
  "Create a JSch identity.  This can be used to check whether the key is
   encrypted."
  ([private-key-path public-key-path]
     (make-identity *ssh-agent* private-key-path public-key-path))
  ([^JSch agent ^String private-key-path ^String public-key-path]
     (logging/tracef "Make identity %s %s" private-key-path public-key-path)
     (reflect/call-method
      com.jcraft.jsch.IdentityFile 'newInstance [String String JSch]
      nil private-key-path public-key-path agent)))

(defn add-identity
  "Add an identity to the agent."
  ([]
     (add-identity *ssh-agent* (default-identity) nil))
  ([private-key]
     (add-identity *ssh-agent* private-key nil))
  ([agent private-key]
     (if (ssh-agent? agent)
       (add-identity agent private-key nil)
       (add-identity *ssh-agent* agent private-key)))
  ([^JSch agent private-key ^String passphrase]
     (assert agent)
     (.addIdentity
      agent
      (if (instance? Identity private-key)
        private-key
        (file-path private-key))
      (and passphrase (.getBytes passphrase))))
  ([^JSch agent ^String name ^bytes private-key ^bytes public-key
    ^bytes passphrase]
     (assert agent)
     (.addIdentity
      agent name private-key public-key passphrase)))

(defn add-identity-with-keychain
  "Add a private key, only if not already known, using the keychain to obtain
   a passphrase if required"
  ([] (add-identity-with-keychain *ssh-agent* (default-identity)))
  ([private-key-path] (add-identity-with-keychain *ssh-agent* private-key-path))
  ([agent private-key-path]
     (when-not (has-identity? agent private-key-path)
       (let [identity (make-identity
                       agent
                       (file-path private-key-path)
                       (str private-key-path ".pub"))]
         (if (.isEncrypted identity)
           (if-let [passphrase (keychain/passphrase private-key-path)]
             (add-identity agent identity passphrase)
             (logging/error "Passphrase required, but none findable."))
           (add-identity agent identity))))))

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
             (add-identity-with-keychain agent default-id)))
         agent)))
  ([private-key passphrase?]
     (let [agent (JSch.)]
       (if passphrase?
         (add-identity agent private-key passphrase?)
         (add-identity-with-keychain agent private-key))
       agent)))

(defmacro with-ssh-agent
  "Bind an ssh-agent for use as identity manager.
The argument vector can be empty, in which case a new agent is created.  If
passed a String or File, then this is passed to the new agent as an identity to
be added.  An existing agent instance can alternatively be passed."
  [[& agent] & body]
  `(binding [*ssh-agent*
             ~(if (seq agent)
                `(let [arg# ~(first agent)]
                   (if (ssh-agent? arg#)
                     arg#
                     (create-ssh-agent ~@agent)))
                `(create-ssh-agent))]
     ~@body))

(defn ^String capitalize
  "Converts first character of the string to upper-case."
  [^String s]
  (if (< (count s) 2)
    (.toUpperCase s)
    (str (.toUpperCase ^String (subs s 0 1))
         (subs s 1))))

(defn- camelize [^String a]
  (apply str (map capitalize (.split a "-"))))

(defn- ^String as-string [arg]
  (cond
   (symbol? arg) (name arg)
   (keyword? arg) (name arg)
   :else (str arg)))

(defn- session-impl
  [^JSch agent hostname username port ^String password options]
  (let [session (.getSession
                 agent
                 (or username (default-user))
                 hostname
                 (or port 22))]
    (when password
      (.setPassword session password))
    (doseq [[k v :as option] options]
      (.setConfig
       session
       (if (string? k)
         k
         (camelize (as-string k)))
       (as-string v)))
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
  [session]
  (.disconnect session)
  (when (instance? com.jcraft.jsch.Session session)
    (when-let [t (reflect/get-field
                  com.jcraft.jsch.Session 'connectThread session)]
      (when (.isAlive t)
        (.interrupt t)))))

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
       (finally
        (disconnect session#)))))

(defn open-channel
  "Open a channel of the specified type in the session."
  [^Session session session-type]
  (.openChannel session (name session-type)))

(defn sftp-channel
  "Open a SFTP channel in the session."
  [^Session session]
  (open-channel session :sftp))

(defn exec-channel
  "Open an Exec channel in the session."
  [^Session session]
  (open-channel session :exec))

(defn shell-channel
  "Open a Shell channel in the session."
  [^Session session]
  (open-channel session :shell))

(def
  ^{:dynamic true
    :doc (str "The buffer size (in bytes) for the piped stream used to implement
    the :stream option for :out. If your ssh commands generate a high volume of
    output, then this buffer size can become a bottleneck. You might also
    increase the frequency with which you read the output stream if this is an
    issue.")}
  *piped-stream-buffer-size* (* 1024 10))

(defn- streams-for-out
  [out]
  (if (= :stream out)
    (let [os (java.io.PipedOutputStream.)]
      [os (java.io.PipedInputStream. os *piped-stream-buffer-size*)])
    [(java.io.ByteArrayOutputStream.) nil]))

(defn- streams-for-in
  []
  (let [os (java.io.PipedInputStream. *piped-stream-buffer-size*)]
    [os (java.io.PipedOutputStream. os)]))

(defn ssh-shell
  "Run a ssh-shell."
  [^Session session in out opts]
  (let [^ChannelShell shell (open-channel session :shell)
        [out-stream out-inputstream] (streams-for-out out)]
    (doto shell
      (.setInputStream
       (if (string? in)
         (java.io.ByteArrayInputStream. (.getBytes (str in ";exit $?;\n")))
         in)
       false)
      (.setOutputStream out-stream))
    (when (contains? opts :pty)
      (reflect/call-method
       com.jcraft.jsch.ChannelSession 'setPty [Boolean/TYPE]
       shell (boolean (opts :pty))))
    (if out-inputstream
      (do
        (connect shell)
        [shell out-inputstream])
      (with-connection shell
        (while (connected? shell)
          (Thread/sleep 100))
        [(.getExitStatus shell)
         (if (= :bytes out)
           (.toByteArray out-stream)
           (.toString out-stream out))]))))

(defn ssh-exec
  "Run a command via ssh-exec."
  [^Session session ^String cmd in out opts]
  (let [^ChannelExec exec (open-channel session :exec)
        [out-stream out-inputstream] (streams-for-out out)
        [err-stream err-inputstream] (streams-for-out out)]
    (doto exec
      (.setInputStream
       (if (string? in)
         (java.io.ByteArrayInputStream. (.getBytes ^String in))
         in)
       false)
      (.setOutputStream out-stream)
      (.setErrStream err-stream)
      (.setCommand cmd))
    (when (contains? opts :pty)
      (reflect/call-method
       com.jcraft.jsch.ChannelSession 'setPty [Boolean/TYPE]
       exec (boolean (opts :pty))))
    (if out-inputstream
      (do
        (connect exec)
        [exec out-inputstream err-inputstream])
      (with-connection exec
        (while (connected? exec)
          (Thread/sleep 100))
        [(.getExitStatus exec)
         (if (= :bytes out)
           (.toByteArray out-stream)
           (.toString out-stream out))
         (if (= :bytes out)
           (.toByteArray err-stream)
           (.toString err-stream out))]))))

(defn forward-local-port
  "Start local port forwarding"
  ([session local-port remote-port remote-host]
     (.setPortForwardingL session local-port remote-host remote-port))
  ([session local-port remote-port]
     (forward-local-port session local-port remote-port "localhost")))

(defn unforward-local-port
  "Remove local port forwarding"
  [session local-port]
  (.delPortForwardingL session local-port))

(defmacro with-local-port-forward
  "Creates a context in which a local SSH tunnel is established for the session.
   (Use before the connection is opened.)"
  [[session local-port remote-port & [remote-host & _]] & body]
  `(try
     (forward-local-port
      ~session ~local-port ~remote-port ~(or remote-host "localhost"))
     ~@body
     (finally
      (unforward-local-port ~session ~local-port))))

(defn default-session [host username port password]
  (doto (session-impl
         (or (and (bound? #'*ssh-agent*) *ssh-agent*) (create-ssh-agent))
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
:out        specify :stream to obtain a an [inputstream shell]
            specify :bytes to obtain a byte array
            or specify a string with an encoding specification for a
            result string.  In the case of :stream, the shell can
            be polled for connected status.
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
         (if (and (opts :return-map) (not= (:out opts) :stream))
           {:exit (first result) :out (second result)}
           result))
       (let [result (ssh-exec
                     session
                     (apply str (interpose " " (:cmd opts)))
                     (:in opts) (:out opts) (dissoc opts :in :out :cmd))]
         (if (and (opts :return-map) (not= (:out opts) :stream))
           {:exit (first result) :out (second result) :err (last result)}
           result)))
     (finally
      (when-not session-given
        (disconnect session))))))

(defn ssh-sftp
  "Obtain a connected ftp channel."
  [^Session session]
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
      4 (. target#
           (~name (first args#) (second args#) (nth args# 2) (nth args# 3)))
      5 (. target#
           (~name (first args#) (second args#) (nth args# 2) (nth args# 3)
                  (nth args# 4)))
      (throw+
       (java.lang.IllegalArgumentException.
        (str "Too many arguments passed.  Limit 5, passed " (count args#)))))))

(def sftp-modemap { :overwrite ChannelSftp/OVERWRITE
                    :resume ChannelSftp/RESUME
                    :append ChannelSftp/APPEND })

(defn ssh-sftp-cmd
  "Command on a ftp channel."
  [^ChannelSftp channel cmd args options]
  (case cmd
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
    :mkdir (.mkdir channel (first args))
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
    (throw+
     (java.lang.IllegalArgumentException. (str "Unknown SFTP command " cmd)))))

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
        channel-given (instance?
                       com.jcraft.jsch.ChannelSftp session-or-hostname)
        session-given (instance?
                       com.jcraft.jsch.Session session-or-hostname)
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

(defn- scp-send-ack
  "Send acknowledgement to the specified output stream"
  ([out] (scp-send-ack out 0))
  ([out code]
     (.write out (byte-array [(byte code)]))
     (.flush out)))

(defn- scp-receive-ack
  "Check for an acknowledgement byte from the given input stream"
  [in]
  (let [code (.read in)]
    (when-not (zero? code)
      (throw+
       {:type :clj-ssh/scp-failure
        :message (format
                  "clj-ssh scp failure: %s"
                  (case code
                    1 "scp error"
                    2 "scp fatal error"
                    -1 "disconnect error"
                    "unknown error"))}))))

(defn- scp-send-command
  "Send command to the specified output stream"
  [out in cmd-string]
  (.write out (.getBytes cmd-string))
  (.flush out)
  (logging/tracef "Sent command %s" cmd-string)
  (scp-receive-ack in)
  (logging/trace "Received ACK"))

(defn- scp-receive-command
  "Receive command on the specified input stream"
  [out in]
  (let [buffer-size 1024
        buffer (byte-array buffer-size)]
    (let [cmd (loop [offset 0]
                (let [n (.read in buffer offset (- buffer-size offset))]
                  (logging/tracef
                   "scp-receive-command: %s"
                   (String. buffer 0 (+ offset n)))
                  (if (= \newline (char (aget buffer (+ offset n -1))))
                    (String. buffer 0 (+ offset n))
                    (recur (+ offset n)))))]
      (logging/tracef "Received command %s" cmd)
      (scp-send-ack out)
      (logging/trace "Sent ACK")
      cmd)))

(defn- scp-copy-file
  "Send acknowledgement to the specified output stream"
  [send recv file {:keys [mode buffer-size preserve]
                   :or {mode 0644 buffer-size 1492 preserve false}}]
  (logging/tracef "Sending %s" (.getAbsolutePath file))
  (when preserve
    (scp-send-command
     send recv
     (format "P %d 0 %d 0\n" (.lastModified file) (.lastModified file))))
  (scp-send-command
   send recv
   (format "C%04o %d %s\n" mode (.length file) (.getName file)))
  (with-open [fs (java.io.FileInputStream. file)]
    (io/copy fs send :buffer-size buffer-size))
  (scp-send-ack send)
  (logging/trace "Sent ACK after send")
  (scp-receive-ack recv)
  (logging/trace "Received ACK after send"))

(defn- scp-copy-dir
  "Send acknowledgement to the specified output stream"
  [send recv dir {:keys [dir-mode] :or {dir-mode 0755} :as options}]
  (logging/trace "Sending directory %s" (.getAbsolutePath dir))
  (scp-send-command
   send recv
   (format "D%04o 0 %s" dir-mode (.getName dir)))
  (doseq [file (.listFiles dir)]
    (cond
     (.isFile file) (scp-copy-file send recv file options)
     (.isDirectory file) (scp-copy-dir send recv file options)
     ))
  (scp-send-ack send)
  (logging/trace "Sent ACK after send")
  (scp-receive-ack recv)
  (logging/trace "Received ACK after send"))

(defn- scp-files
  [paths recursive]
  (let [f (if recursive
            #(java.io.File. %)
            (fn [path]
              (let [file (java.io.File. path)]
                (when (.isDirectory file)
                  (throw+
                   {:type :clj-ssh/scp-directory-copy-requested
                    :message (format
                              "Copy of dir %s requested without recursive flag"
                              path)}))
                file)))]
    (map f paths)))

(defn session-cipher-none
  "Reset the session to use no cipher"
  [session]
  (logging/trace "Set session to prefer none cipher")
  (doto session
    (.setConfig
     "cipher.s2c" "none,aes128-cbc,3des-cbc,blowfish-cbc")
    (.setConfig
     "cipher.c2s" "none,aes128-cbc,3des-cbc,blowfish-cbc")
    (.rekey)))

(defn scp-parse-times
  [cmd]
  (let [s (java.io.StringReader. cmd)]
    (.skip s 1) ;; skip T
    (let [scanner (java.util.Scanner. s)
          mtime (.nextLong scanner)
          zero (.nextInt scanner)
          atime (.nextLong scanner)]
      [mtime atime])))

(defn scp-parse-copy
  [cmd]
  (let [s (java.io.StringReader. cmd)]
    (.skip s 1) ;; skip C or D
    (let [scanner (java.util.Scanner. s)
          mode (.nextInt scanner 8)
          length (.nextLong scanner)
          filename (.next scanner)]
      [mode length filename])))

(defn scp-sink-file
  "Sink a file"
  [send recv file mode length {:keys [buffer-size] :or {buffer-size 2048}}]
  (logging/tracef "Sinking %d bytes to file %s" length (.getPath file))
  (let [buffer (byte-array buffer-size)]
    (with-open [file-stream (java.io.FileOutputStream. file)]
      (loop [length length]
        (let [size (.read recv buffer 0 (min length buffer-size))]
          (when (pos? size)
            (.write file-stream buffer 0 size))
          (when (and (pos? size) (< size length))
            (recur (- length size))))))
    (scp-receive-ack recv)
    (logging/trace "Received ACK after sink of file")
    (scp-send-ack send)
    (logging/trace "Sent ACK after sink of file")))

(defn scp-sink
  "Sink scp commands to file"
  [send recv file times {:as options}]
  (let [cmd (scp-receive-command send recv)]
    (case (first cmd)
      \C (let [[mode length filename] (scp-parse-copy cmd)
               file (if (and (.exists file) (.isDirectory file))
                      (doto (java.io.File. file filename) (.createNewFile))
                      (doto file (.createNewFile)))]
           (scp-sink-file send recv file mode length options)
           (when times
             (.setLastModified file (first times))))
      \T (scp-sink send recv file (scp-parse-times cmd) options)
      \D (let [[mode filename] (scp-parse-copy cmd)
               dir (java.io.File. file filename)]
           (when (and (.exists dir) (not (.isDirectory dir)))
             (.delete dir))
           (when (not (.exists dir))
             (.mkdir dir))
           (scp-sink send recv dir nil options))
      \E nil)))


;; http://blogs.sun.com/janp/entry/how_the_scp_protocol_works
(defn scp-to
  "Copy local path(s) to remote path via scp.

   Options are:

   :username   username to use for authentication
   :password   password to use for authentication
   :port       port to use if no session specified
   :mode       mode, as a 4 digit octal number (default 0644)
   :dir-mode   directory mode, as a 4 digit octal number (default 0755)
   :recursive  flag for recursive operation
   :preserve   flag for preserving mode, mtime and atime. atime is not available
               in java, so is set to mtime. mode is not readable in java."
  [session-or-hostname local-paths remote-path
   & {:keys [username password port mode dir-mode recursive preserve] :as opts}]
  (let [local-paths (if (sequential? local-paths) local-paths [local-paths])
        files (scp-files local-paths recursive)
        session-given (instance? com.jcraft.jsch.Session session-or-hostname)
        session (if session-given
                  session-or-hostname
                  (let [s (default-session
                            session-or-hostname
                            (opts :username)
                            (opts :port)
                            (opts :password))]
                    (if (:cipher-none opts)
                      (session-cipher-none s)
                      s)))]
    (try
      (when (and session (not (connected? session)))
        (connect session))
      (let [[in send] (streams-for-in)
            cmd (format "scp %s -t %s" (:remote-flags opts "") remote-path)
            _ (logging/tracef "scp-to: %s" cmd)
            [exec recv] (ssh-exec session cmd in :stream opts)]
        (logging/tracef
         "scp-to %s %s" (string/join " " local-paths) remote-path)
        (logging/trace "Receive initial ACK")
        (scp-receive-ack recv)
        (doseq [file files]
          (logging/tracef "scp-to: from %s" (.getPath file))
          (if (.isDirectory file)
            (scp-copy-dir send recv file opts)
            (scp-copy-file send recv file opts)))
        (logging/trace "Closing streams")
        (.close send)
        (.close recv)
        (disconnect exec)
        nil)
      (finally
       (when-not session-given
         (disconnect session))))))

(defn scp-from
  "Copy remote path(s) to local path via scp.

   Options are:

   :username   username to use for authentication
   :password   password to use for authentication
   :port       port to use if no session specified
   :mode       mode, as a 4 digit octal number (default 0644)
   :dir-mode   directory mode, as a 4 digit octal number (default 0755)
   :recursive  flag for recursive operation
   :preserve   flag for preserving mode, mtime and atime. atime is not available
               in java, so is set to mtime. mode is not readable in java."
  [session-or-hostname remote-paths local-path
   & {:keys [username password port mode dir-mode recursive preserve] :as opts}]
  (let [remote-paths (if (sequential? remote-paths) remote-paths [remote-paths])
        file (java.io.File. local-path)
        _ (when (and (.exists file)
                     (not (.isDirectory file))
                     (> (count remote-paths) 1))
            (throw+
             {:type :clj-ssh/scp-copy-multiple-files-to-file-requested
              :message (format
                        "Copy of multiple files to file %s requested"
                        local-path)}))
        session-given (instance? com.jcraft.jsch.Session session-or-hostname)
        session (if session-given
                  session-or-hostname
                  (let [s (default-session
                            session-or-hostname
                            (opts :username)
                            (opts :port)
                            (opts :password))]
                    (if (:cipher-none opts)
                      (session-cipher-none s)
                      s)))]
    (try
      (when (and session (not (connected? session)))
        (connect session))
      (let [[in send] (streams-for-in)
            flags {:recursive "-r" :preserve "-p"}
            cmd (format
                 "scp %s -f %s"
                 (:remote-flags
                  opts
                  (string/join
                   " "
                   (->>
                    (select-keys opts [:recursive :preserve])
                    (filter val)
                    (map (fn [k v] (k flags))))))
                 (string/join " " remote-paths))
            _ (logging/tracef "scp-from: %s" cmd)
            [exec recv] (ssh-exec session cmd in :stream opts)]
        (logging/tracef
         "scp-from %s %s" (string/join " " remote-paths) local-path)
        (scp-send-ack send)
        (logging/trace "Sent initial ACK")
        (scp-sink send recv file nil opts)
        (logging/trace "Closing streams")
        (.close send)
        (.close recv)
        (disconnect exec)
        nil)
      (finally
       (when-not session-given
         (disconnect session))))))

(def ^{:private true} key-types {:rsa KeyPair/RSA :dsa KeyPair/DSA})

(defn generate-keypair
  "Generate a keypair, returned as [private public] byte arrays.
   Valid types are :rsa and :dsa.  key-size is in bytes. passphrase
   can be a string or byte array."
  ([key-type key-size passphrase]
     (generate-keypair *ssh-agent* key-type key-size passphrase))
  ([agent key-type key-size passphrase]
     (let [keypair (KeyPair/genKeyPair agent (key-type key-types) key-size)]
       (when passphrase (.setPassphrase keypair passphrase))
       (let [pub-baos (java.io.ByteArrayOutputStream.)
             pri-baos (java.io.ByteArrayOutputStream.)]
         (.writePublicKey keypair pub-baos "")
         (.writePrivateKey keypair pri-baos)
         [(.toByteArray pri-baos) (.toByteArray pub-baos)]))))
