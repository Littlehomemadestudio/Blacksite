"""Bounded embedded CPython runner for ORACLE Studio.

Execution occurs inside the Android app process under the app UID. The line-level
time limit and output cap are guardrails, not an OS security sandbox.
"""
import contextlib
import os
import sys
import time
import traceback

DEFAULT_TIMEOUT_SECONDS = 30.0
MAX_OUTPUT_CHARS = 128 * 1024
MAX_SOURCE_CHARS = 16 * 1024


class _ExecutionLimitExceeded(RuntimeError):
    pass


class _CappedTextBuffer:
    def __init__(self, limit):
        self.limit = limit
        self.parts = []
        self.length = 0
        self.truncated = False
        self.encoding = "utf-8"
        self.errors = "replace"

    def write(self, value):
        text = str(value)
        remaining = max(0, self.limit - self.length)
        if remaining:
            kept = text[:remaining]
            self.parts.append(kept)
            self.length += len(kept)
        if len(text) > remaining:
            self.truncated = True
        return len(text)

    def flush(self):
        return None

    def isatty(self):
        return False

    def getvalue(self):
        return "".join(self.parts)


def run(source, workspace, timeout_seconds=DEFAULT_TIMEOUT_SECONDS,
        max_output_chars=MAX_OUTPUT_CHARS):
    if not isinstance(source, str) or not source.strip():
        return "No Python source supplied."
    if len(source) > MAX_SOURCE_CHARS:
        return "Python source exceeds the 16 KB execution limit."

    timeout = max(0.01, min(float(timeout_seconds), DEFAULT_TIMEOUT_SECONDS))
    output_limit = max(128, min(int(max_output_chars), MAX_OUTPUT_CHARS))
    workspace = os.path.abspath(workspace)
    os.makedirs(workspace, exist_ok=True)

    output = _CappedTextBuffer(output_limit)
    errors = _CappedTextBuffer(output_limit)
    deadline = time.monotonic() + timeout
    old_path = list(sys.path)
    old_argv = list(sys.argv)
    old_trace = sys.gettrace()
    old_cwd = os.getcwd()
    had_workspace_env = "ORACLE_WORKSPACE" in os.environ
    old_workspace_env = os.environ.get("ORACLE_WORKSPACE")

    def guard(frame, event, arg):
        if output.truncated or errors.truncated:
            raise _ExecutionLimitExceeded(
                "Python output limit reached (" + str(output_limit) + " characters per stream)."
            )
        if time.monotonic() >= deadline:
            raise _ExecutionLimitExceeded(
                "Python execution exceeded its " + str(timeout) + " second line-execution limit."
            )
        return guard

    try:
        os.chdir(workspace)
        os.environ["ORACLE_WORKSPACE"] = workspace
        if workspace not in sys.path:
            sys.path.insert(0, workspace)
        sys.argv = ["<oracle>"]
        sys.settrace(guard)
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            exec(compile(source, "<oracle>", "exec"),
                 {"__name__": "__main__", "__file__": "<oracle>"})
    except BaseException as error:
        # Disable tracing before formatting the exception so a reached limit
        # cannot interrupt its own traceback rendering.
        sys.settrace(None)
        if isinstance(error, _ExecutionLimitExceeded):
            errors.write(str(error))
        else:
            traceback.print_exc(file=errors)
    finally:
        sys.settrace(old_trace)
        sys.path[:] = old_path
        sys.argv = old_argv
        try:
            os.chdir(old_cwd)
        except OSError:
            pass
        if had_workspace_env:
            os.environ["ORACLE_WORKSPACE"] = old_workspace_env
        else:
            os.environ.pop("ORACLE_WORKSPACE", None)

    stdout = output.getvalue()
    stderr = errors.getvalue()
    result = stdout
    if stderr:
        result += ("\n" if result else "") + stderr
    if output.truncated:
        result += "\n[stdout truncated at the configured output limit]"
    if errors.truncated:
        result += "\n[stderr truncated at the configured output limit]"
    if not result:
        result = "Execution completed (no output)."
    return result
