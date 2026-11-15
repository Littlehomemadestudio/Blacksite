"""Restricted local Python runner for ORACLE Studio.

This is a computation workspace, not a general-purpose Termux/Linux shell.
It has no shell, filesystem, subprocess, or network APIs exposed to submitted
scripts. The Android app separately handles explicitly granted document access.
"""
import ast
import contextlib
import importlib
import io
import json
import sys
import time
import traceback

_ALLOWED_IMPORTS = {
    "math", "statistics", "decimal", "fractions", "random", "re",
    "json", "csv", "datetime", "collections", "itertools", "functools",
    "operator", "string", "textwrap", "unicodedata", "heapq", "bisect",
}
_BLOCKED_NAMES = {
    "__builtins__", "__import__", "open", "eval", "exec", "compile",
    "input", "globals", "locals", "vars", "getattr", "setattr",
    "delattr", "hasattr", "breakpoint", "help", "dir", "memoryview",
    "quit", "exit",
}
_MAX_NODES = 12000
_MAX_SECONDS = 12
_MAX_OUTPUT = 24000


class _Guard(ast.NodeVisitor):
    def visit_Import(self, node):
        for alias in node.names:
            if alias.name.split(".")[0] not in _ALLOWED_IMPORTS:
                raise ValueError("Import is not available in the local Python workspace: " + alias.name)
        self.generic_visit(node)

    def visit_ImportFrom(self, node):
        if node.level != 0 or not node.module or node.module.split(".")[0] not in _ALLOWED_IMPORTS:
            raise ValueError("Import is not available in the local Python workspace.")
        self.generic_visit(node)

    def visit_Name(self, node):
        if node.id in _BLOCKED_NAMES or node.id.startswith("__"):
            raise ValueError("The local Python workspace blocks access to: " + node.id)
        self.generic_visit(node)

    def visit_Attribute(self, node):
        if node.attr.startswith("_"):
            raise ValueError("Private and dunder attributes are blocked in the local Python workspace.")
        self.generic_visit(node)


def _safe_import(name, globals=None, locals=None, fromlist=(), level=0):
    if level != 0 or not name or name.split(".")[0] not in _ALLOWED_IMPORTS:
        raise ImportError("That module is not installed in the restricted local Python workspace.")
    return importlib.import_module(name)


_SAFE_BUILTINS = {
    "__import__": _safe_import,
    "print": print,
    "len": len,
    "range": range,
    "enumerate": enumerate,
    "zip": zip,
    "map": map,
    "filter": filter,
    "sorted": sorted,
    "reversed": reversed,
    "iter": iter,
    "next": next,
    "sum": sum,
    "min": min,
    "max": max,
    "any": any,
    "all": all,
    "abs": abs,
    "round": round,
    "pow": pow,
    "divmod": divmod,
    "repr": repr,
    "format": format,
    "chr": chr,
    "ord": ord,
    "bin": bin,
    "oct": oct,
    "hex": hex,
    "list": list,
    "tuple": tuple,
    "dict": dict,
    "set": set,
    "frozenset": frozenset,
    "int": int,
    "float": float,
    "str": str,
    "bool": bool,
    "complex": complex,
    "bytes": bytes,
    "bytearray": bytearray,
    "slice": slice,
    "Exception": Exception,
    "ValueError": ValueError,
    "TypeError": TypeError,
    "ZeroDivisionError": ZeroDivisionError,
    "IndexError": IndexError,
    "KeyError": KeyError,
    "RuntimeError": RuntimeError,
    "AssertionError": AssertionError,
}


def run(source):
    if not isinstance(source, str) or not source.strip():
        return json.dumps({"ok": False, "error": "Python source is empty."})
    if len(source) > 30000:
        return json.dumps({"ok": False, "error": "Python source exceeds 30 KB."})

    stdout = io.StringIO()
    stderr = io.StringIO()
    started = time.monotonic()
    try:
        tree = ast.parse(source, filename="<oracle-python>", mode="exec")
        if sum(1 for _ in ast.walk(tree)) > _MAX_NODES:
            raise ValueError("The script is too complex for one local execution.")
        _Guard().visit(tree)

        def timeout_trace(frame, event, arg):
            if time.monotonic() - started > _MAX_SECONDS:
                raise TimeoutError("Python execution exceeded the 12-second limit.")
            return timeout_trace

        namespace = {"__builtins__": _SAFE_BUILTINS}
        sys.settrace(timeout_trace)
        try:
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                exec(compile(tree, "<oracle-python>", "exec"), namespace, namespace)
        finally:
            sys.settrace(None)

        return json.dumps({
            "ok": True,
            "stdout": stdout.getvalue()[-_MAX_OUTPUT:],
            "stderr": stderr.getvalue()[-_MAX_OUTPUT:],
            "durationMs": int((time.monotonic() - started) * 1000),
        })
    except Exception as error:
        sys.settrace(None)
        message = str(error) or error.__class__.__name__
        return json.dumps({
            "ok": False,
            "stdout": stdout.getvalue()[-_MAX_OUTPUT:],
            "stderr": stderr.getvalue()[-_MAX_OUTPUT:],
            "error": message[:2000],
            "traceback": traceback.format_exc(limit=8)[-6000:],
            "durationMs": int((time.monotonic() - started) * 1000),
        })
