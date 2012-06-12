(ns clj-ssh.test-keys)

;; TEST SETUP
;;
;; The tests assume the following setup
;;
;; ssh-keygen -f ~/.ssh/clj_ssh -t rsa -C "key for test clj-ssh" -N ""
;; ssh-keygen -f ~/.ssh/clj_ssh_pp -t rsa -C "key for test clj-ssh" -N "clj-ssh"
;; cp ~/.ssh/authorized_keys ~/.ssh/authorized_keys.bak
;; echo "from=\"127.0.0.1,localhost,0.0.0.0\" $(cat ~/.ssh/clj_ssh.pub)" \
;;   >> ~/.ssh/authorized_keys
;; echo "from=\"127.0.0.1,localhost,0.0.0.0\" $(cat ~/.ssh/clj_ssh_pp.pub)" \
;;   >> ~/.ssh/authorized_keys
;; ssh-add -K ~/.ssh/clj_ssh_pp # add the key to the keychain

(defn private-key-path
  [] (str (. System getProperty "user.home") "/.ssh/clj_ssh"))

(defn public-key-path
  [] (str (. System getProperty "user.home") "/.ssh/clj_ssh.pub"))

(defn encrypted-private-key-path
  [] (str (. System getProperty "user.home") "/.ssh/clj_ssh_pp"))

(defn encrypted-public-key-path
  [] (str (. System getProperty "user.home") "/.ssh/clj_ssh_pp.pub"))

(defn username
  [] (or (. System getProperty "ssh.username")
         (. System getProperty "user.name")))
