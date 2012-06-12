# Release Notes

Current release is 0.3.3

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
