job "termux-shell" {
  datacenters = ["android"]
  type        = "batch"

  constraint {
    attribute = "${attr.driver.termux.ready}"
    value     = "true"
  }

  group "mobile-shell" {
    task "command" {
      driver = "termux"

      env {
        GREETING = "hello from Nomad"
      }

      config {
        command  = "$PREFIX/bin/sh"
        args     = ["-c", "printf '%s\\n' \"$GREETING\"; uname -a"]
        work_dir = "~"
      }
    }
  }
}
