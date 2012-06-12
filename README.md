# clj-ssh

SSH in clojure.  Uses jsch.

## Usage

### REPL

The `clj-ssh.cli` namespace provides some functions for ease of use at the REPL.

    (use 'clj-ssh.ssh)

There is a simple `ssh` function, which by default, will use the system
ssh-agent.

    (ssh "hostname" "ls")

Strict host key checking can be turned off.

    (default-session-options {:strict-host-key-checking :no})

By default, your current username is used.  If your key has a passphrase, and
you are on OSX, then you should be asked for access to your keychain.  If you
are on any other OS without a ssh-agent, you will need to explicitly add your
key to the clj-ssh's ssh-agent with the appropriate add-identity call.

SFTP is supported:

```clj
(sftp "hostname" :put "/from/this/path" "to/this/path")
```

Note that any sftp commands that change the state of the sftp session (such as
cd) do not work with the simplified interface, as a new session is created each
time.

### Non REPL

The `clj-ssh.ssh` namespace should be using SSH from functional code.

```clj
(let [agent (ssh-agent {:use-system-ssh-agent false})]
  (add-identity agent "/user/name/.ssh/id_rsa")
  (let [session (session agent "localhost" {:strict-host-key-checking :no})]
    (with-connection session
      (let [result (ssh session {:in "echo hello"})]
        (println (result :out)))
      (let [result (ssh session "/bin/bash" "-c" "ls" "/")]
        (println (second result))))))
```

```clj
(let [agent (ssh-agent {})]
  (let [session (session agent "localhost" {:strict-host-key-checking :no})]
    (with-connection session
      (let [channel (ssh-sftp session)]
        (with-channel-connection channel
          (sftp channel :cd "/remote/path")
          (sftp channel :put "/some/file" "filename"))))))
```

SSH tunneling is also supported:

```clj
    (let [agent (ssh-agent {:use-system-ssh-agent false})]
      (let [session (session agent "localhost" :strict-host-key-checking :no)]
        (with-connection session
          (with-local-port-forward [session 8080 80]
            (comment do something with port 8080 here)))))
```

## Documentation

[Annotated source](http:/hugoduncan.github.com/clj-ssh/uberdoc.html).
[API](http:/hugoduncan.github.com/clj-ssh/api/0.3/index.html).

## FAQ

Q: What does
"4: Failure @ com.jcraft.jsch.ChannelSftp.throwStatusError(ChannelSftp.java:2289)"
during an sftp transfer signify?

A: Probably a disk full, or permission error.

## Installation

Via [clojars](http://clojars.org) and
[Leiningen](http://github.com/technomancy/leiningen).

    :dependencies [clj-ssh "0.4.0-SNAPSHOT"]

or your favourite maven repository aware tool.

## License

Copyright © 2012 Hugo Duncan

Licensed under [EPL](http://www.eclipse.org/legal/epl-v10.html)
