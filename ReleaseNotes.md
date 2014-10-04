## 0.5.11

- Lock known hosts file
  Try and prevent concurrent read and writes to the known hosts file.

- Fix :out :stream in the cli namespace
  Caller becomes responsible for closing the session.

  Closes #29

## 0.5.10

- Fix session? predicate

- Factor out ssh-exec-proc and ssh-shell-proc
  These provide a lower level interface with more flexible stream handling.

- Add java.awt.headless to default java opts

## 0.5.9

- Publish new jars
  The 0.5.8 jars were not correctly published and are identical to 0.5.7.

## 0.5.8

- Enable introspection of sessions
  Adds the session? predicate for testing for a Session object, an the
  session-hostname and session-port functions for querying a session.

- Enable copying identities between agents
  The copy-identities can be used to copy identities from one agent to
  another.  This is useful to allow copying identities from a system agent
  to a non system agent.

- Add support for jump hosts
  The jump-session function is used to obtain a session that can be
  connected across jump hosts.

  The `the-session` function is added to obtain a jsch session from a
  connected jump-session, or a connected jsch Session, and can be used in
  code that wants to support jump sessions.

- Add fingerprint function on keypairs

- Allow keypair construction from just a public key

- Update tools.logging to 0.2.6

- Update to jsch 0.1.51
  Adds support for private keys in PKCS#8 format.

  Fixes several session crash issues.

- Update to jsch.agentproxy 0.0.7

## 0.5.7

- Update to jsch.agentproxy 0.0.6

## 0.5.6

- Allow generate-keypair to write key files

- Factor out keypair generation
  Simplifies add-identity by factoring out the keypair creation.

- Update to jsch 0.1.50

## 0.5.5

- Wrap open-channel exceptions
  When .openChannel throws an exception, wrap it in an ex-info exception.
  This allows easier procession of the exceptions in consuming code.

## 0.5.4

- Ensure literal keys get a non-blank comment
  When adding a literal key, use "Added by clj-ssh" as the comment.

- Ensure literal key strings are handled correctly


## 0.5.3

- Add ssh/exit-status for querying exit code

- Add type hints to session functions

## 0.5.2

- Update to official jsch.agentproxy relase jars

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
