(ns ^{:author "Hugo Duncan"}
  clj-ssh.ssh
  "API for using SSH in clojure.

## Usage

    (use 'clj-ssh.ssh)

    (let [agent (ssh-agent {})]
      (add-identity agent {:private-key-path path-to-private-key})
      (let [session (session agent hostname :strict-host-key-checking :no)]
        (with-connection session
          (let [result (ssh session {:in commands-string})]
            (println (:out result)))
          (let [result (ssh session {:cmd some-cmd-string})]
            (println (:out result))))))

    (let [agent (ssh-agent {})]
      (let [session (session agent \"localhost\" {:strict-host-key-checking :no})]
        (with-connection session
          (let [channel (ssh-sftp session)]
            (with-channel-connection channel
              (sftp channel :cd \"/remote/path\")
              (sftp channel :put \"/some/file\" \"filename\"))))))"
  (:require
   [clj-ssh.agent :as agent]
   [clj-ssh.keychain :as keychain]
   [clj-ssh.reflect :as reflect]
   [clj-ssh.ssh.protocols :as protocols]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.logging :as logging])
  (:import
   [java.io
    File InputStream OutputStream StringReader
    FileInputStream FileOutputStream
    ByteArrayInputStream ByteArrayOutputStream
    PipedInputStream PipedOutputStream]
   [com.jcraft.jsch
    JSch Session Channel ChannelShell ChannelExec ChannelSftp JSchException
    Identity IdentityFile IdentityRepository Logger KeyPair LocalIdentityRepository]))

;;; forward jsch's logging to java logging
(def ^{:dynamic true}
  ssh-log-levels
  (atom
   {com.jcraft.jsch.Logger/DEBUG :trace
    com.jcraft.jsch.Logger/INFO  :debug
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

;;; Helpers
(defn- ^String file-path [string-or-file]
  (if (string? string-or-file)
    string-or-file
    (.getPath ^File string-or-file)))

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

(def ^java.nio.charset.Charset ascii
  (java.nio.charset.Charset/forName "US-ASCII"))

(def ^java.nio.charset.Charset utf-8
  (java.nio.charset.Charset/forName "UTF-8"))

(defn- ^{:tag (Class/forName "[B")} as-bytes
  "Return arg as a byte array.  arg must be a string or a byte array."
  [arg]
  (if (string? arg)
    (.getBytes ^String arg ascii)
    arg))

(defn ssh-agent?
  "Predicate to test for an ssh-agent."
  [object] (instance? JSch object))

;;; Session extension

;; This is only relevant if you want to support using jump hosts.  If
;; you do, the you should always use the `the-session` to get the jsch
;; session object once connected.

;; This is here since JSch Session has a package scoped constructor,
;; and doesn't implement an interface, so provides no means for
;; extending it.

(extend-protocol protocols/Session
  Session
  (connect
    ([session] (.connect session))
    ([session timeout] (.connect session timeout)))
  (connected? [session] (.isConnected session))
  (disconnect [session] (.disconnect session))
  (session [session] session))

(defn ^Session the-session
  "Return the JSch session for the given session."
  [session]
  (protocols/session session))

(defn session?
  "Predicate to test for a session"
  [x]
  (satisfies? protocols/Session x))

;;; Agent
(def ^:private hosts-file
  "Something to lock to tray and prevent concurrent updates/reads to
  hosts file."
  (Object.))

(defn ssh-agent
  "Create a ssh-agent. By default a system ssh-agent is preferred."
  [{:keys [use-system-ssh-agent ^String known-hosts-path]
    :or {use-system-ssh-agent true
         known-hosts-path (str (. System getProperty "user.home")
                               "/.ssh/known_hosts")}}]
  (let [agent (JSch.)]
    (when use-system-ssh-agent
      (agent/connect agent))
    (when known-hosts-path
      (locking hosts-file
        (.setKnownHosts agent known-hosts-path)))
    agent))

;;; Identities
(defn has-identity?
  "Check if the given identity is present."
  [^JSch agent name]
  (some #(= name %) (.getIdentityNames agent)))

(defn ^Identity make-identity
  "Create a JSch identity.  This can be used to check whether the key is
   encrypted."
  [^JSch agent ^String private-key-path ^String public-key-path]
  (logging/tracef "Make identity %s %s" private-key-path public-key-path)
  (reflect/call-method
   com.jcraft.jsch.IdentityFile 'newInstance [String String JSch]
   nil private-key-path public-key-path agent))

(defn ^KeyPair keypair
  "Return a KeyPair object for the given options.

:private-key       A string specifying the private key
:public-key        A string specifying the public key
:private-key-path  A string specifying a path to the private key
:public-key-path   A string specifying a path to the public key
:passphrase        A byte array containing the passphrase
:comment           A comment for the key"
  [^JSch agent {:keys [^String public-key
                       ^String private-key
                       ^String public-key-path
                       ^String private-key-path
                       ^String comment
                       ^bytes passphrase]
                :as options}]
  {:pre [(map? options)]}
  (cond
   private-key
   (let [^KeyPair keypair
         (KeyPair/load agent (as-bytes private-key) (as-bytes public-key))]
     (when passphrase
       (.decrypt keypair passphrase))
     (.setPublicKeyComment keypair comment)
     keypair)

   public-key
   (let [^KeyPair keypair (KeyPair/load agent nil (as-bytes public-key))]
     (.setPublicKeyComment keypair comment)
     keypair)

   (and public-key-path private-key-path)
   (let [keypair (KeyPair/load agent private-key-path public-key-path)]
     (when passphrase
       (.decrypt keypair passphrase))
     (.setPublicKeyComment keypair comment)
     keypair)

   private-key-path
   (let [keypair (KeyPair/load agent private-key-path)]
     (when passphrase
       (.decrypt keypair passphrase))
     (.setPublicKeyComment keypair comment)
     keypair)

   :else
   (throw
    (ex-info
     "Don't know how to create keypair"
     {:reason :do-not-know-how-to-create-keypair
      :args options}))))

(defn fingerprint
  "Return a keypair's fingerprint."
  [^KeyPair keypair]
  (.getFingerPrint keypair))

(defn copy-identities
  [^JSch from-agent ^JSch to-agent]
  (let [^IdentityRepository ir (.getIdentityRepository from-agent)]
    (doseq [^Identity id (.getIdentities ir)]
      (.addIdentity to-agent id nil))))

;; JSch's IdentityFile has a private constructor that would let us avoid this
;; were it public.
(deftype KeyPairIdentity [^JSch jsch ^String identity ^KeyPair kpair]
  Identity
  (^boolean setPassphrase [_ ^bytes passphrase] (.. kpair (decrypt passphrase)))
  (getPublicKeyBlob [_] (.. kpair getPublicKeyBlob))
  (^bytes getSignature [_ ^bytes data] (.. kpair (getSignature data)))
  (getAlgName [_]
    (String. (reflect/call-method KeyPair 'getKeyTypeName [] kpair)))
  (getName [_] identity)
  (isEncrypted [_] (.. kpair isEncrypted))
  (clear [_] (.. kpair dispose)))

(defn add-identity
  "Add an identity to the agent.  The identity is passed with the :identity
keyword argument, or constructed from the other keyword arguments.

:private-key       A string specifying the private key
:public-key        A string specifying the public key
:private-key-path  A string specifying a path to the private key
:public-key-path   A string specifying a path to the public key
:identity          A jsch Identity object (see make-identity)
:passphrase        A byte array containing the passphrase"
  [^JSch agent {:keys [^String name
                       ^String public-key
                       ^String private-key
                       ^String public-key-path
                       ^String private-key-path
                       ^Identity identity
                       ^bytes passphrase]
                :as options}]
  {:pre [(map? options)]}
  (let [^String comment (or name private-key-path public-key)
        ^Identity identity
        (or identity
            (KeyPairIdentity.
             agent comment (keypair agent (assoc options :comment comment))))]
    (.addIdentity agent identity passphrase)))

(defn add-identity-with-keychain
  "Add a private key, only if not already known, using the keychain to obtain
   a passphrase if required"
  [^JSch agent {:keys [^String name
                       ^String public-key-path
                       ^String private-key-path
                       ^Identity identity
                       ^bytes passphrase]
                :as options}]
  (logging/debugf
   "add-identity-with-keychain has-identity? %s" (has-identity? agent name))
  (when-not (has-identity? agent name)
    (let [name (or name private-key-path)
          public-key-path (or public-key-path (str private-key-path ".pub"))
          identity (if private-key-path
                     (make-identity
                      agent
                      (file-path private-key-path)
                      (file-path public-key-path)))]
      (logging/debugf
       "add-identity-with-keychain is-encrypted? %s" (.isEncrypted identity))
      (if (.isEncrypted identity)
        (if-let [passphrase (keychain/passphrase private-key-path)]
          (add-identity agent (assoc options :passphrase passphrase))
          (do
            (logging/error "Passphrase required, but none findable.")
            (throw
             (ex-info
              (str "Passphrase required for key " name ", but none findable.")
              {:reason :passphrase-not-found
               :key-name name}))))
        (add-identity agent options)))))

;;; Sessions
(defn- init-session
  "Initialise options on a session"
  [^Session session ^String password options]
  (when password
    (.setPassword session password))
  (doseq [[k v :as option] options]
    (.setConfig
     session
     (if (string? k)
       k
       (camelize (as-string k)))
     (as-string v))))

(defn- ^Session session-impl
  [^JSch agent hostname username port ^String password options]
  (doto (.getSession agent username hostname port)
    (init-session password options)))

(defn- session-options
  [options]
  (dissoc options :username :port :password :agent))

(defn ^Session session
  "Start a SSH session.
Requires hostname.  You can also pass values for :username, :password and :port
keys.  All other option key pairs will be passed as SSH config options."
  [^JSch agent hostname
   {:keys [port username password] :or {port 22} :as options}]
  (session-impl
   agent hostname
   (or username (System/getProperty "user.name"))
   port
   password
   (session-options options)))

(defn ^String session-hostname
  "Return the hostname for a session"
  [^Session session]
  (.getHost session))

(defn ^int session-port
  "Return the port for a session"
  [^Session session]
  (.getPort session))

(defn forward-remote-port
  "Start remote port forwarding"
  ([^Session session remote-port local-port ^String local-host]
     (.setPortForwardingR
      session (int remote-port) local-host (int local-port)))
  ([session remote-port local-port]
     (forward-remote-port session remote-port local-port "localhost")))

(defn unforward-remote-port
  "Remove remote port forwarding"
  [^Session session remote-port]
  (.delPortForwardingR session remote-port))

(defmacro with-remote-port-forward
  "Creates a context in which a remote SSH tunnel is established for the
  session. (Use after the connection is opened.)"
  [[session remote-port local-port & [local-host & _]] & body]
  `(try
     (forward-remote-port
      ~session ~remote-port ~local-port ~(or local-host "localhost"))
     ~@body
     (finally
      (unforward-remote-port ~session ~remote-port))))

(defn forward-local-port
  "Start local port forwarding. Returns the actual local port."
  ([^Session session local-port remote-port remote-host]
     (.setPortForwardingL session local-port remote-host remote-port))
  ([session local-port remote-port]
     (forward-local-port session local-port remote-port "localhost")))

(defn unforward-local-port
  "Remove local port forwarding"
  [^Session session local-port]
  (.delPortForwardingL session local-port))

(defmacro with-local-port-forward
  "Creates a context in which a local SSH tunnel is established for the session.
   (Use after the connection is opened.)"
  [[session local-port remote-port & [remote-host & _]] & body]
  `(try
     (forward-local-port
      ~session ~local-port ~remote-port ~(or remote-host "localhost"))
     ~@body
     (finally
      (unforward-local-port ~session ~local-port))))

(defn connect
  "Connect a session."
  ([session]
     (locking hosts-file
       (protocols/connect session)))
  ([session timeout]
     (locking hosts-file
       (protocols/connect session timeout))))

(defn disconnect
  "Disconnect a session."
  [session]
  (protocols/disconnect session)
  (when-let [^Thread t (and (instance? Session session)
                            (reflect/get-field
                             com.jcraft.jsch.Session 'connectThread session))]
    (when (.isAlive t)
      (.interrupt t))))

(defn connected?
  "Predicate used to test for a connected session."
  [session]
  (protocols/connected? session))

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

;;; Jump Hosts
(defn- jump-connect [agent hosts sessions timeout]
  (let [host (first hosts)
        s (session agent (:hostname host) (dissoc host :hostname))
        throw-e (fn [e s]
                  (throw
                   (ex-info
                    (str "Failed to connect "
                         (.getUserName s) "@"
                         (.getHost s) ":"
                         (.getPort s)
                         " " (pr-str (into [] (.getIdentityNames agent)))
                         " " (pr-str hosts))
                    {:hosts hosts}
                    e)))]
    (swap! sessions (fnil conj []) s)
    (try
      (connect s timeout)
      (catch Exception e (throw-e e s)))
    (.setDaemonThread s true)
    (loop [hosts (rest hosts)
           prev-s s]
      (if-let [{:keys [hostname port username password]
                :or {port 22}
                :as options}
               (first hosts)]
        (let [p (forward-local-port prev-s 0 port hostname)
              options (-> options
                          (dissoc :hostname)
                          (assoc :port p))
              s (session agent "localhost" options)]
          (.setDaemonThread s true)
          (.setHostKeyAlias s hostname)
          (swap! sessions conj s)
          (try
            (connect s timeout)
            (catch Exception e (throw-e e s)))
          (recur (rest hosts) s))))))

(defn- jump-connected? [sessions]
  (seq @sessions))

(defn- jump-disconnect
  [sessions]
  (doseq [s (reverse @sessions)]
    (.disconnect s))
  (reset! sessions nil))

(defn- jump-the-session
  [sessions]
  (assert (jump-connected? sessions) "not connected")
  (last @sessions))

(deftype JumpHostSession [agent hosts sessions timeout]
  protocols/Session
  (connect [session] (protocols/connect session timeout))
  (connect [session timeout] (jump-connect agent hosts sessions timeout))
  (connected? [session] (jump-connected? sessions))
  (disconnect [session] (jump-disconnect sessions))
  (session [session] (jump-the-session sessions)))

;; http://www.jcraft.com/jsch/examples/JumpHosts.java.html
(defn jump-session
  "Connect via a sequence of jump hosts.  Returns a session.  Once the
session is connected, use `the-session` to get a jsch Session object.

Each host is a map with :hostname, :username, :password and :port
keys.  All other key pairs in each host map will be passed as SSH
config options."
  [^JSch agent hosts {:keys [timeout]}]
  (when-not (seq hosts)
    (throw (ex-info "Must provide at least one host to connect to"
                    {:hosts hosts})))
  (JumpHostSession. agent hosts (atom []) (or timeout 0)))

;;; Channels
(defn connect-channel
  "Connect a channel."
  [^Channel channel]
  (.connect channel))

(defn disconnect-channel
  "Disconnect a session."
  [^Channel channel]
  (.disconnect channel))

(defn connected-channel?
  "Predicate used to test for a connected channel."
  [^Channel channel]
  (.isConnected channel))

(defmacro with-channel-connection
  "Creates a context in which the channel is connected. Ensures the channel is
  disconnected on exit."
  [channel & body]
  `(let [channel# ~channel]
     (try
       (when-not (connected-channel? channel#)
         (connect-channel channel#))
       ~@body
       (finally
        (disconnect-channel channel#)))))

(defn open-channel
  "Open a channel of the specified type in the session."
  [^Session session session-type]
  (try
    (.openChannel session (name session-type))
    (catch JSchException e
      (let [msg (.getMessage e)]
        (cond
         (= msg "session is down")
         (throw (ex-info (format "clj-ssh open-channel failure: %s" msg)
                         {:type :clj-ssh/open-channel-failure
                          :reason :clj-ssh/session-down}
                         e))
         (= msg "channel is not opened.")
         (throw (ex-info
                 (format
                  "clj-ssh open-channel failure: %s (possible session timeout)"
                  msg)
                 {:type :clj-ssh/open-channel-failure
                  :reason :clj-ssh/channel-open-failed}
                 e))
         :else (throw (ex-info (format "clj-ssh open-channel failure: %s" msg)
                               {:type :clj-ssh/open-channel-failure
                                :reason :clj-ssh/unknown}
                               e)))))))

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

(defn exit-status
  "Return the exit status of a channel."
  [^Channel channel]
  (.getExitStatus channel))

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
    (let [os (PipedOutputStream.)]
      [os (PipedInputStream. os (int *piped-stream-buffer-size*))])
    [(ByteArrayOutputStream.) nil]))

(defn- streams-for-in
  []
  (let [os (PipedInputStream. (int *piped-stream-buffer-size*))]
    [os (PipedOutputStream. os)]))

(defn string-stream
  "Return an input stream with content from the string s."
  [^String s]
  {:pre [(string? s)]}
  (ByteArrayInputStream. (.getBytes s utf-8)))

(defn ssh-shell-proc
  "Run a ssh-shell."
  [^Session session in {:keys [agent-forwarding pty out err] :as opts}]
  {:pre [in]}
  (let [^ChannelShell shell (open-channel session :shell)]
    (doto shell
      (.setInputStream in false))
    (when out
      (.setOutputStream shell out))
    (when (contains? opts :pty)
      (.setPty shell (boolean (opts :pty))))
    (when (contains? opts :agent-forwarding)
      (.setAgentForwarding shell (boolean (opts :agent-forwarding))))
    (connect-channel shell)
    {:channel shell
     :out (or out (.getInputStream shell))
     :in (or in (.getOutputStream shell))}))

(defn ssh-shell
  "Run a ssh-shell."
  [^Session session in out opts]
  (let [[out-stream out-inputstream] (streams-for-out out)
        resp (ssh-shell-proc
              session
              (if (string? in) (string-stream (str in ";exit $?;\n")) in)
              (merge {:out out-stream} opts))
        ^ChannelShell shell (:channel resp)]
    (if out-inputstream
      {:channel shell :out-stream out-inputstream}
      (with-channel-connection shell
        (while (connected-channel? shell)
          (Thread/sleep 100))
        {:exit (.getExitStatus shell)
         :out (if (= :bytes out)
                (.toByteArray ^ByteArrayOutputStream out-stream)
                (.toString out-stream))}))))

(defn ssh-exec-proc
  "Run a command via exec, returning a map with the process streams."
  [^Session session ^String cmd
   {:keys [agent-forwarding pty in out err] :as opts}]
  (let [^ChannelExec exec (open-channel session :exec)]
    (doto exec
      (.setCommand cmd)
      (.setInputStream in false))
    (when (contains? opts :pty)
      (.setPty exec (boolean (opts :pty))))
    (when (contains? opts :agent-forwarding)
      (.setAgentForwarding exec (boolean (opts :agent-forwarding))))

    (when out
      (.setOutputStream exec out))
    (when err
      (.setErrStream exec err))
    (let [resp {:channel exec
                :out (or out (.getInputStream exec))
                :err (or err (.getErrStream exec))
                :in (or in (.getOutputStream exec))}]
      (connect-channel exec)
      resp)))

(defn ssh-exec
  "Run a command via ssh-exec."
  [^Session session ^String cmd in out opts]
  (let [[^PipedOutputStream out-stream
         ^PipedInputStream out-inputstream] (streams-for-out out)
        [^PipedOutputStream err-stream
         ^PipedInputStream err-inputstream] (streams-for-out out)
        proc (ssh-exec-proc
              session cmd
              (merge
               {:in (if (string? in) (string-stream in) in)
                :out out-stream
                :err err-stream}
               opts))
        ^ChannelExec exec (:channel proc)]
    (if out-inputstream
      {:channel exec
       :out-stream out-inputstream
       :err-stream err-inputstream}
      (do (while (connected-channel? exec)
            (Thread/sleep 100))
          {:exit (.getExitStatus exec)
           :out (if (= :bytes out)
                  (.toByteArray ^ByteArrayOutputStream out-stream)
                  (.toString out-stream))
           :err (if (= :bytes out)
                  (.toByteArray ^ByteArrayOutputStream err-stream)
                  (.toString err-stream))}))))

(defn ssh
  "Execute commands over ssh.

Options are:

:cmd        specifies a command string to exec.  If no cmd is given, a shell
            is started and input is taken from :in.
:in         specifies input to the remote shell. A string or a stream.
:out        specify :stream to obtain a an [inputstream shell]
            specify :bytes to obtain a byte array
            or specify a string with an encoding specification for a
            result string.  In the case of :stream, the shell can
            be polled for connected status.

sh returns a map of
              :exit => sub-process's exit code
              :out  => sub-process's stdout (as byte[] or String)
              :err  => sub-process's stderr (as byte[] or String)"
  [session {:keys [cmd in out] :as options}]
  (let [connected (connected? session)]
    (try
      (when-not connected
        (connect session))
      (if cmd
        (ssh-exec session cmd in out (dissoc options :in :out :cmd))
        (ssh-shell session in out (dissoc options :in :out :cmd))))))

(defn ssh-sftp
  "Obtain a connected ftp channel."
  [^Session session]
  {:pre (connected? session)}
  (let [channel (open-channel session :sftp)]
    (connect-channel channel)
    channel))

(defmacro memfn-varargs [name klass]
  `(fn [^{:tag ~klass} target# args#]
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
      (throw
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
    :realpath (.realpath channel (first args))
    :get-home (.getHome channel)
    :get-server-version (.getServerVersion channel)
    :get-extension (.getExtension channel (first args))
    :get (let [args (if (options :with-monitor)
                      (conj args (options :with-monitor))
                      args)
               args (if (options :mode)
                      (conj args (sftp-modemap (options :mode)))
                      args)]
           ((memfn-varargs get ChannelSftp) channel args))
    :put (let [args (if (options :with-monitor)
                      (conj args (options :with-monitor))
                      args)
               args (if (options :mode)
                      (conj args (sftp-modemap (options :mode)))
                      args)]
           ((memfn-varargs put ChannelSftp) channel args))
    (throw
     (java.lang.IllegalArgumentException. (str "Unknown SFTP command " cmd)))))

(defn sftp
  "Execute SFTP commands.

  sftp host-or-session options cmd & args

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
:symlink"
  [session-or-channel {:keys [with-monitor modes] :as opts} cmd & args]
  (let [channel-given (instance? com.jcraft.jsch.ChannelSftp session-or-channel)
        session-given (instance? com.jcraft.jsch.Session session-or-channel)
        session (when session-given session-or-channel)
        channel (if channel-given
                  session-or-channel
                  (ssh-sftp session))]
    (try
      (when (and session (not (connected? session)))
        (connect session))
      (ssh-sftp-cmd channel cmd (vec args) opts)
      (finally
        (when-not channel-given
          (disconnect-channel channel))
        (when-not (or session-given channel-given)
          (disconnect session))))))

(defn- scp-send-ack
  "Send acknowledgement to the specified output stream"
  ([^OutputStream out] (scp-send-ack out 0))
  ([^OutputStream out code]
     (.write out (byte-array [(byte code)]))
     (.flush out)))

(defn- scp-receive-ack
  "Check for an acknowledgement byte from the given input stream"
  [^InputStream in]
  (let [code (.read in)]
    (when-not (zero? code)
      (throw
       (ex-info
        (format
         "clj-ssh scp failure: %s"
         (case code
           1 "scp error"
           2 "scp fatal error"
           -1 "disconnect error"
           "unknown error"))
        {:type :clj-ssh/scp-failure})))))

(defn- scp-send-command
  "Send command to the specified output stream"
  [^OutputStream out ^InputStream in ^String cmd-string]
  (.write out (.getBytes (str cmd-string "\n")))
  (.flush out)
  (logging/tracef "Sent command %s" cmd-string)
  (scp-receive-ack in)
  (logging/trace "Received ACK"))

(defn- scp-receive-command
  "Receive command on the specified input stream"
  [^OutputStream out ^InputStream in]
  (let [buffer-size 1024
        buffer (byte-array buffer-size)]
    (let [cmd (loop [offset 0]
                (let [n (.read in buffer offset (- buffer-size offset))]
                  (logging/tracef
                   "scp-receive-command: %s"
                   (String. buffer (int 0) (int (+ offset n))))
                  (if (= \newline (char (aget buffer (+ offset n -1))))
                    (String. buffer (int 0) (int (+ offset n)))
                    (recur (+ offset n)))))]
      (logging/tracef "Received command %s" cmd)
      (scp-send-ack out)
      (logging/trace "Sent ACK")
      cmd)))

(defn- scp-copy-file
  "Send acknowledgement to the specified output stream"
  [^OutputStream send ^InputStream recv ^File file {:keys [mode buffer-size preserve]
                   :or {mode 0644 buffer-size 1492 preserve false}}]

  (when preserve
    (scp-send-command
     send recv
     (format "P%d 0 %d 0" (.lastModified file) (.lastModified file))))
  (scp-send-command
   send recv
   (format "C%04o %d %s" mode (.length file) (.getName file)))
  (logging/tracef "Sending %s" (.getAbsolutePath file))
  (io/copy file send :buffer-size buffer-size)
  (scp-send-ack send)
  (logging/trace "Receiving ACK after send")
  (scp-receive-ack recv))

(defn- scp-copy-dir
  "Send acknowledgement to the specified output stream"
  [send recv ^File dir {:keys [dir-mode] :or {dir-mode 0755} :as options}]
  (logging/tracef "Sending directory %s" (.getAbsolutePath dir))
  (scp-send-command
   send recv
   (format "D%04o 0 %s" dir-mode (.getName dir)))
  (doseq [^File file (.listFiles dir)]
    (cond
     (.isFile file) (scp-copy-file send recv file options)
     (.isDirectory file) (scp-copy-dir send recv file options)))
  (scp-send-command send recv "E"))

(defn- scp-files
  [paths recursive]
  (let [f (if recursive
            #(File. ^String %)
            (fn [^String path]
              (let [file (File. path)]
                (when (.isDirectory file)
                  (throw
                   (ex-info
                    (format
                     "Copy of dir %s requested without recursive flag" path)
                    {:type :clj-ssh/scp-directory-copy-requested})))
                file)))]
    (map f paths)))

(defn session-cipher-none
  "Reset the session to use no cipher"
  [^Session session]
  (logging/trace "Set session to prefer none cipher")
  (doto session
    (.setConfig
     "cipher.s2c" "none,aes128-cbc,3des-cbc,blowfish-cbc")
    (.setConfig
     "cipher.c2s" "none,aes128-cbc,3des-cbc,blowfish-cbc")
    (.rekey)))

(defn scp-parse-times
  [cmd]
  (let [s (StringReader. cmd)]
    (.skip s 1) ;; skip T
    (let [scanner (java.util.Scanner. s)
          mtime (.nextLong scanner)
          zero (.nextInt scanner)
          atime (.nextLong scanner)]
      [mtime atime])))

(defn scp-parse-copy
  [cmd]
  (let [s (StringReader. cmd)]
    (.skip s 1) ;; skip C or D
    (let [scanner (java.util.Scanner. s)
          mode (.nextInt scanner 8)
          length (.nextLong scanner)
          filename (.next scanner)]
      [mode length filename])))

(defn scp-sink-file
  "Sink a file"
  [^OutputStream send ^InputStream recv
   ^File file mode length {:keys [buffer-size] :or {buffer-size 2048}}]
  (logging/tracef "Sinking %d bytes to file %s" length (.getPath file))
  (let [buffer (byte-array buffer-size)]
    (with-open [file-stream (FileOutputStream. file)]
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
  [^OutputStream send ^InputStream recv ^File file times {:as options}]
  (let [cmd (scp-receive-command send recv)]
    (case (first cmd)
      \C (let [[mode length ^String filename] (scp-parse-copy cmd)
               file (if (and (.exists file) (.isDirectory file))
                      (doto (File. file filename) (.createNewFile))
                      (doto file (.createNewFile)))]
           (scp-sink-file send recv file mode length options)
           (when times
             (.setLastModified file (first times))))
      \T (scp-sink send recv file (scp-parse-times cmd) options)
      \D (let [[mode ^String filename] (scp-parse-copy cmd)
               dir (File. file filename)]
           (when (and (.exists dir) (not (.isDirectory dir)))
             (.delete dir))
           (when (not (.exists dir))
             (.mkdir dir))
           (scp-sink send recv dir nil options))
      \E nil)))


;; http://blogs.sun.com/janp/entry/how_the_scp_protocol_works
;; https://blogs.oracle.com/janp/entry/how_the_scp_protocol_works
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
  [session local-paths remote-path
   & {:keys [username password port mode dir-mode recursive preserve] :as opts}]
  (let [local-paths (if (sequential? local-paths) local-paths [local-paths])
        files (scp-files local-paths recursive)]
    (when (and session (not (connected? session)))
      (connect session))
    (let [[^PipedInputStream in
           ^PipedOutputStream send] (streams-for-in)
          cmd (format "scp %s %s -t %s" (:remote-flags opts "") (if recursive "-r" "") remote-path)
          _ (logging/tracef "scp-to: %s" cmd)
          {:keys [^ChannelExec channel ^PipedInputStream out-stream]}
          (ssh-exec session cmd in :stream opts)
          exec channel
          recv out-stream]
      (logging/tracef
       "scp-to %s %s" (string/join " " local-paths) remote-path)
      (logging/trace "Receive initial ACK")
      (scp-receive-ack recv)
      (doseq [^File file files]
        (logging/tracef "scp-to: from %s" (.getPath file))
        (if (.isDirectory file)
          (scp-copy-dir send recv file opts)
          (scp-copy-file send recv file opts)))
      (logging/trace "Closing streams")
      (.close send)
      (.close recv)
      (disconnect-channel exec)
      nil)))

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
  [session remote-paths ^String local-path
   & {:keys [username password port mode dir-mode recursive preserve] :as opts}]
  (let [remote-paths (if (sequential? remote-paths) remote-paths [remote-paths])
        file (File. local-path)
        _ (when (and (.exists file)
                     (not (.isDirectory file))
                     (> (count remote-paths) 1))
            (throw
             (ex-info
              (format "Copy of multiple files to file %s requested" local-path)
              {:type :clj-ssh/scp-copy-multiple-files-to-file-requested})))]
    (when (and session (not (connected? session)))
      (connect session))
    (let [[^PipedInputStream in
           ^PipedOutputStream send] (streams-for-in)
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
                  (map (comp flags key)))))
               (string/join " " remote-paths))
          _ (logging/tracef "scp-from: %s" cmd)
          {:keys [^ChannelExec channel
                  ^PipedInputStream out-stream]}
          (ssh-exec session cmd in :stream opts)
          exec channel
          recv out-stream]
      (logging/tracef
       "scp-from %s %s" (string/join " " remote-paths) local-path)
      (scp-send-ack send)
      (logging/trace "Sent initial ACK")
      (scp-sink send recv file nil opts)
      (logging/trace "Closing streams")
      (.close send)
      (.close recv)
      (disconnect-channel exec)
      nil)))

(def ^{:private true} key-types {:rsa KeyPair/RSA :dsa KeyPair/DSA})

(defn generate-keypair
  "Generate a keypair, returned as [private public] byte arrays.
   Valid types are :rsa and :dsa.  key-size is in bytes. passphrase can be a
   string or byte array.  Optionally writes the keypair to the paths specified
   using the :private-key-path and :public-key-path keys."
  [agent key-type key-size passphrase
   & {:keys [comment private-key-path public-key-path]}]
  (let [keypair (KeyPair/genKeyPair agent (key-type key-types) key-size)]
    (when passphrase
      (.setPassphrase keypair passphrase))
    (when public-key-path
      (.writePublicKey keypair public-key-path comment))
    (when private-key-path
      (.writePrivateKey keypair private-key-path))
    (let [pub-baos (ByteArrayOutputStream.)
          pri-baos (ByteArrayOutputStream.)]
      (.writePublicKey keypair pub-baos "")
      (.writePrivateKey keypair pri-baos)
      [(.toByteArray pri-baos) (.toByteArray pub-baos)])))
