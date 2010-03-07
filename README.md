# clj-ssh

SSH in clojure.  Uses jsch.  Provides a ssh function that tries to look similar
to clojure.contrib.shell/sh.

## Usage

The top level namespace is `clj-ssh.ssh`

    (use 'clj-ssh)

There is a simple `ssh` function, which by default, will try and use a id_rsa
key in your $HOME/.ssh directory.

    (ssh "hostname" "ls")

Strict host key checking can be turned off.

    (default-session-options {:strict-host-key-checking :no})

More advance usage is possible.

    (with-ssh-agent []
      (add-identity "/user/name/.ssh/id_dsa")
      (let [session (session "localhost" :strict-host-key-checking :no)]
        (with-connection session
          (let [result (ssh session :in "echo hello" :result-map true)]
            (println (result :out)))
          (let [result (ssh session "/bin/bash" "-c" "ls" "/")]
            (println (second result))))))

## Installation

Via maven and the [clojars](http://clojars.org), or
[Leiningen](http://github.com/technomancy/leiningen).

## License

Licensed under [EPL](http://www.eclipse.org/legal/epl-v10.html)

## TODO

port forwarding
environment setup
sftp
scp
