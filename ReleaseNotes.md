# Release Notes

Current release is 0.5.1

## 0.5.1

- Fix adding key string identities for ssh-agent
  Adding string based ssh keys to an ssh-agent was broken.

## 0.5.0

- Require clojure 1.4.0
  Drops usage of slingshot.

## 0.4.4

- Make ssh key test more robust
  The add-identity-test ssh-agent case was failing for no apparent reason.

- Remove some reflection warnings

## 0.4.3

- Use passphrase when adding key to agent, and honour the key name

## 0.4.2

- Update to jsch 0.1.49 and enable adding keys to ssh-agent. Support for
  adding passphrase-less keys only.

## 0.4.1

- add option to ssh-agent for :known-hosts-path. Fixes #16.

- remap log levels to be less verbose by default. Fixes #17

## 0.4.0

- Split out clj-ssh.cli

  clj-ssh.ssh is designed for composability and programmatic use. It takes 
  map arguments for options and is fully functional.

  clj-ssh.cli is intended to simplify repl usage. It takes variadic
  arguments for options and uses dynamic vars to provide defaults.

## 0.3.3

- Add a :agent-forwarding option
  A boolean value is passed with :agent-forwarding to clj-ssh.ssh/ssh.

- Add support for system ssh-agent
  Support the system ssh-agent (or pageant on windows when using putty) via 
  jsch-agent-proxy. Introduces a new agent function, clj-ssh.ssh/ssh-agent.

## 0.3.2

- Add remote port forwarding support

- Fix documentation for with-local-port-forward

- Allow specification of session options as strings
  This should allow options like:
   (default-session-options {"GSSAPIAuthentication" "no"})

## 0.3.1

- Allow clj-ssh to work with a wide range of slingshot versions
  Tested with slingshot 0.2.0 and 0.10.1

- Added SSH tunneling.

## 0.3.0

* Changes

- Remove use of monolithic contrib
  In preparation for clojure 1.3. Use slingshot instead of
  contrib.condition. Use local copy of contrib.reflect.

- Switch to tools.logging

- Allow specification of PipedInputStream buffer size
  The buffer size (in bytes) for the piped stream used to implement the
  :stream option for :out. If the ssh commands generate a high volume of
  output, then this buffer size can become a bottleneck. The buffer size
  can be specified by binding *piped-stream-buffer-size*, and defaults to
  10Kb.

- Add scp-from and scp-to, for copy files over ssh-exec
  Add support for scp using ssh-exec.   copies files from a remote machine.
   copies to a remote machine.

- Drop clojure 1.1.0 support
