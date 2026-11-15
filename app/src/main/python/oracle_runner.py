"""Unrestricted embedded CPython runner for ORACLE Studio.

This is a normal Python interpreter embedded in the Android app process.
It intentionally does not AST-filter imports or remove builtins. The process
still runs under Android's app UID and filesystem/OS permissions.
"""
import contextlib
import io
import os
import sys
import traceback

def run(source, workspace):
    if not isinstance(source, str) or not source.strip():
        return "No Python source supplied."
    os.makedirs(workspace, exist_ok=True)
    os.chdir(workspace)
    os.environ["ORACLE_WORKSPACE"] = workspace
    output = io.StringIO()
    errors = io.StringIO()
    old_path = list(sys.path)
    old_argv = list(sys.argv)
    try:
        if workspace not in sys.path:
            sys.path.insert(0, workspace)
        sys.argv = ["<oracle>"]
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            exec(compile(source, "<oracle>", "exec"), {"__name__": "__main__", "__file__": "<oracle>"})
    except BaseException:
        traceback.print_exc(file=errors)
    finally:
        sys.path[:] = old_path
        sys.argv = old_argv
    stdout = output.getvalue()
    stderr = errors.getvalue()
    result = stdout
    if stderr:
        result = result + ("\n" if result else "") + stderr
    if not result:
        result = "Execution completed (no output)."
    return result
