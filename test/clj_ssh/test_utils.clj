(ns clj-ssh.test-utils
  (:use
    [clj-ssh.ssh :only [ssh-log-levels]])
  (:import com.jcraft.jsch.Logger))

(def debug-log-levels
  {com.jcraft.jsch.Logger/DEBUG :debug
   com.jcraft.jsch.Logger/INFO  :debug
   com.jcraft.jsch.Logger/WARN  :debug
   com.jcraft.jsch.Logger/ERROR :error
   com.jcraft.jsch.Logger/FATAL :fatal})

(defn quiet-ssh-logging [f]
  (let [levels @ssh-log-levels]
    (try
      (reset! ssh-log-levels debug-log-levels)
      (f)
      (finally
        (reset! ssh-log-levels levels)))))

(defn sftp-monitor
  "Create a SFTP progress monitor"
  []
  (let [operation (atom nil)
        source (atom nil)
        destination (atom nil)
        number (atom nil)
        done (atom false)
        progress (atom 0)
        continue (atom true)]
    [(proxy [com.jcraft.jsch.SftpProgressMonitor] []
       (init [op src dest max]
         (do
           (reset! operation op)
           (reset! source src)
           (reset! destination dest)
           (reset! number max)
           (reset! done false)))
       (count [n]
         (reset! progress n)
         @continue)
       (end []
         (reset! done true)))
     [operation source destination number done progress continue]]))

(defn sftp-monitor-done [state]
  @(nth state 4))

(defn cwd
  [] (. System getProperty "user.dir"))

(defn home
  [] (. System getProperty "user.home"))
