# Release Notes

Current release is 0.3.0

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
