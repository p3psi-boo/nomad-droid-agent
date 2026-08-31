package com.nomad.droid.termux

/**
 * The subset of Termux's public RUN_COMMAND contract used by Nomad Droid.
 *
 * Values are pinned to TermuxConstants.java at commit
 * 3b66f8799635a4dba4a206563048ff0e6792c487 (v0.53.0):
 * https://github.com/termux/termux-app/blob/3b66f8799635a4dba4a206563048ff0e6792c487/termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java
 */
internal object TermuxContract {
    const val PACKAGE_NAME = "com.termux"
    const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

    const val EXTRA_RESULT_BUNDLE = "result"
    const val EXTRA_STDOUT = "stdout"
    const val EXTRA_STDOUT_ORIGINAL_LENGTH = "stdout_original_length"
    const val EXTRA_STDERR = "stderr"
    const val EXTRA_STDERR_ORIGINAL_LENGTH = "stderr_original_length"
    const val EXTRA_EXIT_CODE = "exitCode"
    const val EXTRA_ERR = "err"
    const val EXTRA_ERRMSG = "errmsg"

    const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    const val TERMUX_HOME = "/data/data/com.termux/files/home"
    const val SHELL = "$TERMUX_PREFIX/bin/sh"
    const val TRUE = "$TERMUX_PREFIX/bin/true"
}
