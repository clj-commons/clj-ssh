(ns clj-ssh.proxy
  "Provides an SSH proxy"
  (:import
   [com.jcraft.jsch ProxySOCKS4 ProxySOCKS5]))

;; based on http://sourceforge.net/apps/mediawiki/jsch/index.php?title=ProxySSH

;; (defrecord SSHProxy [gateway channel istream ostream]
;;   Proxy
;;   (close [_] (.disconnect channel))
;;   (connect [_ _ host port timeout]
;;     (reset! channel (.openChannel gateway "direct-tcpip"))
;;     (doto channel
;;       (.setHost host)
;;       (.setPort port))
;;     (reset! iStream (.getInputStream @channel))
;;     (reset! oStream (.getOutputStream @channel))
;;     (.connect @channel))
;;   (getInputStream [_] iStream)
;;   (getOutputStream [_] oStream)
;;   (getSocket [_]))


(defn socks5-proxy
  "Return an SOCKS5 proxy using the specified host and port."
  [host port]
  (ProxySOCKS5. host port))

(defn socks4-proxy
  "Return an SOCKS5 proxy using the specified host and port."
  [host port]
  (ProxySOCKS4. host port))

(defn set-session-proxy
  "Set the proxy for the given session."
  [session proxy]
  (.setProxy session proxy))
