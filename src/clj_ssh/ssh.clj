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
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.logging :as logging])
  (:use
   [slingshot.slingshot :only [throw+]])
  (:import
   [java.io
    File InputStream OutputStream StringReader
    FileInputStream FileOutputStream
    ByteArrayInputStream ByteArrayOutputStream
    PipedInputStream PipedOutputStream]
   [com.jcraft.jsch
    JSch Session Channel ChannelShell ChannelExec ChannelSftp
    Identity IdentityFile Logger KeyPair]))

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

(defn ssh-agent?
  "Predicate to test for an ssh-agent."
  [object] (instance? JSch object))

;;; Agent
(defn ssh-agent
  "Create a ssh-agent. By default a system ssh-agent is preferred."
  [{:keys [use-system-ssh-agent known-hosts-path]
    :or {use-system-ssh-agent true
         known-hosts-path (str (. System getProperty "user.home") "/.ssh/known_hosts")}}]
  (let [agent (JSch.)]
    (when use-system-ssh-agent
      (agent/connect agent))
    (when known-hosts-path
      (.setKnownHosts agent known-hosts-path))
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

(defn add-identity
  "Add an identity to the agent.

:private-key       A string specifying the private key
:public-key        A string specifying the public key
:private-key-path  A string specifying a path to the private key
:public-key-path   A string specifying a path to the public key
:identity          A jsch Identity object (see make-identity)
:passphrase        A byte array containing the passphrase"
  [^JSch agent {:keys [^String name
                       ^String public-key
                       ^String private-key
                       public-key-path
                       private-key-path
                       ^Identity identity
                       ^bytes passphrase]
                :as options}]
  {:pre [(map? options)]}
  (let [name (or name private-key-path public-key)]
    (cond
      identity
      (.addIdentity agent identity passphrase)

      (and public-key private-key)
      (.addIdentity agent name private-key public-key passphrase)

      (and public-key-path private-key-path)
      (.addIdentity
       agent
       (file-path private-key-path) (file-path public-key-path) passphrase)

      private-key-path
      (.addIdentity agent (file-path private-key-path) passphrase)

      :else
      (throw+
       {:reason :do-not-know-how-to-add-identity
        :args options}
       "Don't know how to add identity"))))

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
            (throw+
             {:reason :passphrase-not-found
              :key-name name}
             "Passphrase required for key %s, but none findable." name)))
        (add-identity agent options)))))

;;; Sessions
(defn- session-impl
  [^JSch agent hostname username port ^String password options]
  (let [session (.getSession agent username hostname port)]
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
  [^JSch agent hostname
   {:keys [port username password] :or {port 22} :as options}]
  (session-impl
   agent hostname
   (or username (System/getProperty "user.name"))
   port
   password
   (dissoc options :username :port :password :agent)))

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
  "Start local port forwarding"
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
  ([^Session session]
     (.connect session))
  ([^Session session timeout]
     (.connect session timeout)))

(defn disconnect
  "Disconnect a session."
  [^Session session]
  (.disconnect session)
  (when-let [^Thread t (reflect/get-field
                        com.jcraft.jsch.Session 'connectThread session)]
    (when (.isAlive t)
      (.interrupt t))))

(defn connected?
  "Predicate used to test for a connected session."
  [^Session session]
  (.isConnected session))

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
    (let [os (PipedOutputStream.)]
      [os (PipedInputStream. os (int *piped-stream-buffer-size*))])
    [(ByteArrayOutputStream.) nil]))

(defn- streams-for-in
  []
  (let [os (PipedInputStream. (int *piped-stream-buffer-size*))]
    [os (PipedOutputStream. os)]))

(defn ssh-shell
  "Run a ssh-shell."
  [^Session session in out opts]
  (let [^ChannelShell shell (open-channel session :shell)
        [out-stream out-inputstream] (streams-for-out out)]
    (doto shell
      (.setInputStream
       (if (string? in)
         (ByteArrayInputStream. (.getBytes (str in ";exit $?;\n")))
         in)
       false)
      (.setOutputStream out-stream))
    (when (contains? opts :pty)
      (reflect/call-method
       com.jcraft.jsch.ChannelSession 'setPty [Boolean/TYPE]
       shell (boolean (opts :pty))))
    (when (contains? opts :agent-forwarding)
      (reflect/call-method
       com.jcraft.jsch.ChannelSession 'setAgentForwarding [Boolean/TYPE]
       shell (boolean (opts :agent-forwarding))))
    (if out-inputstream
      (do
        (connect-channel shell)
        {:channel shell :out-stream out-inputstream})
      (with-channel-connection shell
        (while (connected-channel? shell)
          (Thread/sleep 100))
        {:exit (.getExitStatus shell)
         :out (if (= :bytes out)
                (.toByteArray out-stream)
                (.toString out-stream))}))))

(defn ssh-exec
  "Run a command via ssh-exec."
  [^Session session ^String cmd in out opts]
  (let [^ChannelExec exec (open-channel session :exec)
        [^PipedOutputStream out-stream
         ^PipedInputStream out-inputstream] (streams-for-out out)
        [^PipedOutputStream err-stream
         ^PipedInputStream err-inputstream] (streams-for-out out)]
    (doto exec
      (.setInputStream
       (if (string? in)
         (ByteArrayInputStream. (.getBytes ^String in))
         in)
       false)
      (.setOutputStream out-stream)
      (.setErrStream err-stream)
      (.setCommand cmd))
    (when (contains? opts :pty)
      (reflect/call-method
       com.jcraft.jsch.ChannelSession 'setPty [Boolean/TYPE]
       exec (boolean (opts :pty))))
    (when (contains? opts :agent-forwarding)
      (reflect/call-method
       com.jcraft.jsch.ChannelSession 'setAgentForwarding [Boolean/TYPE]
       exec (boolean (opts :agent-forwarding))))
    (if out-inputstream
      (do
        (connect-channel exec)
        {:channel exec
         :out-stream out-inputstream
         :err-stream err-inputstream})
      (with-channel-connection exec
        (while (connected-channel? exec)
          (Thread/sleep 100))
        {:exit (.getExitStatus exec)
         :out (if (= :bytes out)
                (.toByteArray out-stream)
                (.toString out-stream))
         :err (if (= :bytes out)
                (.toByteArray err-stream)
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
    (throw+
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
  [^OutputStream out ^InputStream in ^String cmd-string]
  (.write out (.getBytes cmd-string))
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
  [send recv ^File file {:keys [mode buffer-size preserve]
                   :or {mode 0644 buffer-size 1492 preserve false}}]
  (logging/tracef "Sending %s" (.getAbsolutePath file))
  (when preserve
    (scp-send-command
     send recv
     (format "P %d 0 %d 0\n" (.lastModified file) (.lastModified file))))
  (scp-send-command
   send recv
   (format "C%04o %d %s\n" mode (.length file) (.getName file)))
  (with-open [fs (FileInputStream. file)]
    (io/copy fs send :buffer-size buffer-size))
  (scp-send-ack send)
  (logging/trace "Sent ACK after send")
  (scp-receive-ack recv)
  (logging/trace "Received ACK after send"))

(defn- scp-copy-dir
  "Send acknowledgement to the specified output stream"
  [send recv ^File dir {:keys [dir-mode] :or {dir-mode 0755} :as options}]
  (logging/trace "Sending directory %s" (.getAbsolutePath dir))
  (scp-send-command
   send recv
   (format "D%04o 0 %s" dir-mode (.getName dir)))
  (doseq [^File file (.listFiles dir)]
    (cond
     (.isFile file) (scp-copy-file send recv file options)
     (.isDirectory file) (scp-copy-dir send recv file options)))
  (scp-send-ack send)
  (logging/trace "Sent ACK after send")
  (scp-receive-ack recv)
  (logging/trace "Received ACK after send"))

(defn- scp-files
  [paths recursive]
  (let [f (if recursive
            #(File. ^String %)
            (fn [^String path]
              (let [file (File. path)]
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
          cmd (format "scp %s -t %s" (:remote-flags opts "") remote-path)
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
            (throw+
             {:type :clj-ssh/scp-copy-multiple-files-to-file-requested
              :message (format
                        "Copy of multiple files to file %s requested"
                        local-path)}))]
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
                  (map (fn [k v] (k flags))))))
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
   Valid types are :rsa and :dsa.  key-size is in bytes. passphrase
   can be a string or byte array."
  [agent key-type key-size passphrase]
  (let [keypair (KeyPair/genKeyPair agent (key-type key-types) key-size)]
    (when passphrase (.setPassphrase keypair passphrase))
    (let [pub-baos (ByteArrayOutputStream.)
          pri-baos (ByteArrayOutputStream.)]
      (.writePublicKey keypair pub-baos "")
      (.writePrivateKey keypair pri-baos)
      [(.toByteArray pri-baos) (.toByteArray pub-baos)])))
