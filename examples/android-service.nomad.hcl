job "android-service" {
  datacenters = ["android"]
  type        = "service"

  constraint {
    attribute = "${attr.nomad.droid.base}"
    value     = "true"
  }

  group "mobile" {
    task "workload" {
      driver = "android"

      artifact {
        source      = "https://downloads.example.com/workload.apk"
        destination = "local/workload.apk"

        options {
          checksum = "sha256:REPLACE_WITH_THE_APK_SHA256"
        }
      }

      config {
        package  = "com.example.workload"
        service  = ".NomadWorkService"
        install  = true
        replace  = true
        apk_path = "local/workload.apk"
        sha256   = "REPLACE_WITH_THE_APK_SHA256"
      }
    }
  }
}
