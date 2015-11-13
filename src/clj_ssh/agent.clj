(ns clj-ssh.agent
  "Agent integration (using jsch-agent-proxy)"
  (:require
    [clojure.tools.logging :as logging])
  (:import
    com.jcraft.jsch.JSch
    [com.jcraft.jsch.agentproxy
     AgentProxyException Connector RemoteIdentityRepository]
    [com.jcraft.jsch.agentproxy.connector
     PageantConnector SSHAgentConnector]
    com.jcraft.jsch.agentproxy.usocket.JNAUSocketFactory))

(defn sock-agent-connector
  []
  (when (SSHAgentConnector/isConnectorAvailable)
    (try
      (let [usf (JNAUSocketFactory.)]
        (SSHAgentConnector. usf))
      (catch AgentProxyException e
        (logging/warnf
          e "Failed to load JNA connector, although SSH_AUTH_SOCK is set")))))

(defn pageant-connector
  []
  (when (PageantConnector/isConnectorAvailable)
    (try
      (PageantConnector.)
      (catch AgentProxyException e
        (logging/warn
          e "Failed to load Pageant connector, although running on windows")))))

(defn connect
  "Connect the specified jsch object to the system ssh-agent."
  [^JSch jsch]
  (when-let [connector (or (sock-agent-connector) (pageant-connector))]
    (doto jsch
      ;(.setConfig "PreferredAuthentications" "publickey")
      (.setIdentityRepository (RemoteIdentityRepository. connector)))))
