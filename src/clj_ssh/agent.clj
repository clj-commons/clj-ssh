(ns clj-ssh.agent
  "Agent integration (using jsch-agent-proxy)"
  (:require
   [clojure.tools.logging :as logging])
  (:import
   [com.jcraft.jsch
    JSch AgentProxyException AgentIdentityRepository
    PageantConnector SSHAgentConnector JUnixSocketFactory]))

(defn sock-agent-connector
  []
  (try
    (let [con (SSHAgentConnector.)]
      (when (.isAvailable con)
        con))
    (catch AgentProxyException e
      (logging/warnf
       e "Failed to load JNA connector, although SSH_AUTH_SOCK is set"))))

(defn pageant-connector
  []
  (try
    (let [con (PageantConnector.)]
      (when (.isAvailable con)
        con))
    (catch AgentProxyException e
      (logging/warn
       e "Failed to load Pageant connector, although running on windows"))))

(defn connect
  "Connect the specified jsch object to the system ssh-agent."
  [^JSch jsch]
  (when-let [connector (or (sock-agent-connector) (pageant-connector))]
    (doto jsch
      ;(.setConfig "PreferredAuthentications" "publickey")
      (.setIdentityRepository (AgentIdentityRepository. connector)))))
