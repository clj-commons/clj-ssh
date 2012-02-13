# clj-ssh

SSH in clojure.  Uses jsch.  Provides a ssh function that tries to look similar
to clojure.contrib.shell/sh.

## Usage

The top level namespace is `clj-ssh.ssh`

    (use 'clj-ssh.ssh)

There is a simple `ssh` function, which by default, will try and use a id_rsa
key in your $HOME/.ssh directory.

    (ssh "hostname" "ls")

Strict host key checking can be turned off.

    (default-session-options {:strict-host-key-checking :no})

By default, your current username and id_rsa key are used.  If your key has a
passphrase, and you are on OSX, then you should be asked for access to your
keychain.  If you are on any other OS, you will need to explicitly add your key
to the clj-ssh's ssh-agent with the appropriate add-identity call.

More advance usage is possible.

    (with-ssh-agent []
      (add-identity "/user/name/.ssh/id_dsa")
      (let [session (session "localhost" :strict-host-key-checking :no)]
        (with-connection session
          (let [result (ssh session :in "echo hello" :result-map true)]
            (println (result :out)))
          (let [result (ssh session "/bin/bash" "-c" "ls" "/")]
            (println (second result))))))

SFTP is supported, both with a simple interface,

     (sftp "hostname" :put "/from/this/path" "to/this/path")

as well as more advanced usage.

    (with-ssh-agent []
      (let [session (session "localhost" :strict-host-key-checking :no)]
        (with-connection session
          (let [channel (ssh-sftp session)]
            (with-connection channel
              (sftp channel :cd "/remote/path")
              (sftp channel :put "/some/file" "filename"))))))

Note that any sftp commands that change the state of the sftp session (such as
cd) do not work with the simplified interface, as a new session is created each
time.

SSH tunneling is also supported:

    (with-ssh-agent []
      (let [session (session "localhost" :strict-host-key-checking :no)]
        (with-local-tunnel session 8080 80
          (with-connection session
            (while (connected? session)
              (Thread/sleep 100))))))

or more conveniently:

    (with-ssh-agent []
      (let [session (session "localhost" :strict-host-key-checking :no)]
        (ssh-tunnel session 8080 80)))

## Documentation

[Annotated source](http:/hugoduncan.github.com/clj-ssh/uberdoc.html).
[API](http:/hugoduncan.github.com/clj-ssh/api/0.3/index.html).

## FAQ

Q: Why doesn't clj-ssh integrate with the OS's ssh agent?

A: Java has no access to the Unix domain socket used by the system ssh-agent.

Q: What does  "4: Failure @ com.jcraft.jsch.ChannelSftp.throwStatusError(ChannelSftp.java:2289)" during an sftp transfer signify?

A: Probably a disk full, or permission error.

## Installation

Via [clojars](http://clojars.org) and
[Leiningen](http://github.com/technomancy/leiningen).

    :dependencies [clj-ssh "0.3.1"]

or your favourite maven repository aware tool.

## License

Licensed under [EPL](http://www.eclipse.org/legal/epl-v10.html)

## TODO

port forwarding
environment setup
sftp
scp
