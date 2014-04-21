(ns clj-ssh.ssh.protocols
  "Protocols for ssh")

(defprotocol Session
  "Provides a protocol for a session."
  (connect [x] [x timeout] "Connect the session")
  (connected? [x] "Predicate for a connected session")
  (disconnect [x] "Disconnect the session")
  (session [x] "Return a Jsch Session for the session"))
