package com.nomad.droid.termux

internal object TermuxCommandSpec {
    private val environmentName = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun normalizePath(value: String): String = when {
        value == "~" -> TermuxContract.TERMUX_HOME
        value.startsWith("~/") -> TermuxContract.TERMUX_HOME + value.drop(1)
        value == "\$PREFIX" -> TermuxContract.TERMUX_PREFIX
        value.startsWith("\$PREFIX/") -> TermuxContract.TERMUX_PREFIX + value.drop(7)
        else -> value
    }

    fun validateEnvironment(environment: Map<String, String>) {
        environment.keys.forEach { name ->
            require(environmentName.matches(name)) { "Invalid environment variable name: $name" }
        }
    }

    fun startArguments(
        pidFile: String,
        command: String,
        arguments: List<String>,
        environment: Map<String, String>,
    ): Array<String> {
        require(command.isNotBlank()) { "command is required" }
        validateEnvironment(environment)
        val sortedEnvironment = environment.toSortedMap().map { (name, value) -> "$name=$value" }
        return buildList {
            add("-c")
            add(START_SCRIPT)
            add("nomad-droid")
            add(pidFile)
            add(sortedEnvironment.size.toString())
            addAll(sortedEnvironment)
            add(normalizePath(command))
            addAll(arguments)
        }.toTypedArray()
    }

    fun stopArguments(pidFile: String, force: Boolean): Array<String> = arrayOf(
        "-c",
        STOP_SCRIPT,
        "nomad-droid-stop",
        pidFile,
        if (force) "KILL" else "TERM",
    )

    // User values are positional parameters, never interpolated into either script.
    internal val START_SCRIPT = """
pidfile=§1
env_count=§2
shift 2
while [ "§env_count" -gt 0 ]; do
  export "§1" || exit 125
  shift
  env_count=§((env_count - 1))
done
mkdir -p "§{pidfile%/*}" || exit 125
rm -f -- "§pidfile"
set -m
"§@" &
child=§!
printf '%s\n%s\n' "§§" "§child" > "§pidfile" || {
  kill -TERM "-§child" 2>/dev/null
  exit 125
}
terminate() {
  kill -TERM "-§child" 2>/dev/null
  wait "§child"
  code=§?
  rm -f -- "§pidfile"
  exit "§code"
}
trap terminate TERM INT HUP
wait "§child"
code=§?
rm -f -- "§pidfile"
exit "§code"
""".replace('§', '$')

    internal val STOP_SCRIPT = """
pidfile=§1
signal=§2
[ -r "§pidfile" ] || exit 0
{
  IFS= read -r wrapper
  IFS= read -r child
} < "§pidfile"
case "§wrapper:§child" in
  *[!0-9:]*|:*|*:)
    rm -f -- "§pidfile"
    exit 126
    ;;
esac
if ! grep -F -z -q -- "§pidfile" "/proc/§wrapper/cmdline" 2>/dev/null; then
  rm -f -- "§pidfile"
  exit 0
fi
kill -"§signal" "-§child" 2>/dev/null || true
kill -"§signal" "§wrapper" 2>/dev/null || true
exit 0
""".replace('§', '$')
}
