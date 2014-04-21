# clj-ssh

SSH in clojure.  Uses jsch.

## Usage

### REPL

The `clj-ssh.cli` namespace provides some functions for ease of use at the REPL.

```clj
(use 'clj-ssh.cli)
```

Use `ssh` to execute a command, say `ls`, on a remote host "my-host",

```clj
(ssh "my-host" "ls")
  => {:exit 0 :out "file1\nfile2\n" :err "")
```

By default this will use the system ssh-agent to obtain your ssh keys, and it
uses your current username, but this can be specified:

```clj
(ssh "my-host" "ls" :username "remote-user")
  => {:exit 0 :out "file1\nfile2\n" :err "")
```

Strict host key checking can be turned off:

```clj
(default-session-options {:strict-host-key-checking :no})
```

SFTP is also supported. For example, to copy a local file to a remote host
"my-host":

```clj
(sftp "my-host" :put "/from/this/path" "to/this/path")
```

Note that any sftp commands that change the state of the sftp session (such as
cd) do not work with the simplified interface, as a new session is created each
time.

If your key has a passphrase, you will need to explicitly add your key either to
the system's ssh-agent, or to clj-ssh's ssh-agent with the appropriate
`add-identity` call.

### Non REPL

The `clj-ssh.ssh` namespace should be used for SSH from functional code.

```clj
(let [agent (ssh-agent {})]
  (let [session (session agent "host-ip" {:strict-host-key-checking :no})]
    (with-connection session
      (let [result (ssh session {:in "echo hello"})]
        (println (result :out)))
      (let [result (ssh session {:cmd "ls"}]
        (println (second result)))))))
```

The above example shows using `:in` to pass commands to a shell, and using
`:cmd` to exec a command without a shell. When using `:cmd` you can still pass
a stream or a string to `:in` to be used as the process' standard input.

By default, the system ssh-agent is used, which means the ssh keys you use at
the command line level should automatically be picked up (this should also work
with `pageant` on windows).

You can forward the ssh-agent, which allows you to run ssh based commands on the
remote host using the credentials in your local ssh-agent:

```clj
(let [agent (ssh-agent {})]
  (let [session (session agent "host-ip" {:strict-host-key-checking :no})]
    (with-connection session
      (let [result (ssh session {:in "ssh somehost ls" :agent-forwarding true})]
        (println (result :out))))))
```

If you prefer not to use the system ssh-agent, or one is not running on your
system, then a local, isolated ssh-agent can be used.

```clj
(let [agent (ssh-agent {:use-system-ssh-agent false})]
  (add-identity agent {:private-key-path "/user/name/.ssh/id_rsa"})
  (let [session (session agent "host-ip" {:strict-host-key-checking :no})]
    (with-connection session
      (let [result (ssh session {:in "echo hello"})]
        (println (result :out)))))
```

SFTP is supported:

```clj
(let [agent (ssh-agent {})]
  (let [session (session agent "host-ip" {:strict-host-key-checking :no})]
    (with-connection session
      (let [channel (ssh-sftp session)]
        (with-channel-connection channel
          (sftp channel {} :cd "/remote/path")
          (sftp channel {} :put "/some/file" "filename"))))))
```

SSH tunneling is also supported:

```clj
    (let [agent (ssh-agent {})]
      (let [session (session agent "host-ip" {:strict-host-key-checking :no})]
        (with-connection session
          (with-local-port-forward [session 8080 80]
            (comment do something with port 8080 here)))))
```

Jump hosts can be used with the `jump-session`.  Once the session is
connected, the `the-session` function can be used to obtain a session
that can be used with `ssh-exec`, etc.  The `the-session` function can
be used on a session returned by `session`, so you can write code that
works with both a `jump-session` session and a single host session.

```clj
(let [s (jump-session
         (ssh-agent {})
         [{:hostname "host1" :username "user"
           :strict-host-key-checking :no}
          {:hostname "final-host" :username "user"
           :strict-host-key-checking :no}]
         {})]
  (with-connection s
    (ssh-exec (the-session s) "ls" "" "" {}))
```



## Documentation

[Annotated source](http:/hugoduncan.github.com/clj-ssh/0.5/annotated/uberdoc.html).
[API](http:/hugoduncan.github.com/clj-ssh/0.5/api/index.html).

## FAQ

Q: What does
"4: Failure @ com.jcraft.jsch.ChannelSftp.throwStatusError(ChannelSftp.java:2289)"
during an sftp transfer signify?

A: Probably a disk full, or permission error.

### TTY's and background processes

Some useful links about ssh and background processes:

- [Snail book](http://www.snailbook.com/faq/background-jobs.auto.html)
- [ssh -t question on stackoverflow](http://stackoverflow.com/questions/14679178/why-does-ssh-wait-for-my-subshells-without-t-and-kill-them-with-t)
- [sudo and tty question on stackoverflow](http://stackoverflow.com/questions/8441637/to-run-sudo-commands-on-a-ec2-instance)

Thanks to [Ryan Stradling](http://github.com/rstradling) for these.

## Installation

Via [clojars](http://clojars.org) and
[Leiningen](http://github.com/technomancy/leiningen).

    :dependencies [clj-ssh "0.5.7"]

or your favourite maven repository aware tool.

## Tests

The test rely on several keys being authorized on localhost:

```shell
ssh-keygen -f ~/.ssh/clj_ssh -t rsa -C "key for test clj-ssh" -N ""
ssh-keygen -f ~/.ssh/clj_ssh_pp -t rsa -C "key for test clj-ssh" -N "clj-ssh"
cp ~/.ssh/authorized_keys ~/.ssh/authorized_keys.bak
echo "from=\"localhost\" $(cat ~/.ssh/clj_ssh.pub)" >> ~/.ssh/authorized_keys
echo "from=\"localhost\" $(cat ~/.ssh/clj_ssh_pp.pub)" >> ~/.ssh/authorized_keys
```

The `clj_ssh_pp` key should have a passphrase, and should be registered with your `ssh-agent`.

```shell
ssh-add ~/.ssh/clj_ssh_pp
```

On OS X, use:

```shell
ssh-add -K ~/.ssh/clj_ssh_pp
```

## License

Copyright © 2012 Hugo Duncan

Licensed under [EPL](http://www.eclipse.org/legal/epl-v10.html)
