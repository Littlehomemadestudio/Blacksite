"""Restricted local Python convenience runtime; not a security boundary against hostile code."""
import ast, contextlib, io, sys
_ALLOWED={"math","statistics","decimal","fractions","itertools","collections","functools","re","json","datetime","random","string","textwrap"}
_BLOCKED={"open","exec","eval","compile","input","breakpoint","globals","locals","vars","getattr","setattr","delattr"}
_SAFE={"abs","all","any","bool","bytes","callable","chr","dict","divmod","enumerate","filter","float","format","frozenset","hash","hex","int","isinstance","issubclass","iter","len","list","map","max","min","next","oct","ord","pow","print","range","repr","reversed","round","set","slice","sorted","str","sum","tuple","type","zip","Exception","BaseException","StopIteration","NotImplementedError","object","property","classmethod","staticmethod","super","ArithmeticError","AssertionError","AttributeError","IndexError","KeyError","NameError","RuntimeError","TypeError","ValueError","ZeroDivisionError"}
class _Budget(Exception): pass
def _import(name,globals=None,locals=None,fromlist=(),level=0):
    if level or name.split(".")[0] not in _ALLOWED: raise ImportError("Module is disabled: "+name)
    return __import__(name,globals,locals,fromlist,level)
def run(source):
    if not isinstance(source,str) or not source.strip(): return "No Python source supplied."
    if len(source)>48000: return "Source exceeds 48 KB."
    try:
        tree=ast.parse(source,mode="exec")
        for node in ast.walk(tree):
            names=[a.name for a in node.names] if isinstance(node,ast.Import) else ([node.module or ""] if isinstance(node,ast.ImportFrom) else [])
            if any(n.split(".")[0] not in _ALLOWED for n in names): return "Blocked import. Available: "+", ".join(sorted(_ALLOWED))
            if isinstance(node,ast.Name) and (node.id.startswith("__") or node.id in _BLOCKED): return "Blocked name: "+node.id
            if isinstance(node,ast.Attribute) and node.attr.startswith("__"): return "Dunder access is disabled."
    except SyntaxError as e: return "SyntaxError: "+str(e)
    out=io.StringIO(); builtins=__builtins__ if isinstance(__builtins__,dict) else vars(__builtins__)
    safe={k:builtins[k] for k in _SAFE if k in builtins}; safe["__import__"]=_import; safe["__build_class__"]=builtins["__build_class__"]
    env={"__builtins__":safe,"__name__":"__oracle__"}; ticks=[0]; old=sys.gettrace()
    def trace(frame,event,arg):
        if event=="line":
            ticks[0]+=1
            if ticks[0]>200000: raise _Budget("Execution budget exceeded (200,000 lines).")
        return trace
    try:
        sys.settrace(trace)
        with contextlib.redirect_stdout(out),contextlib.redirect_stderr(out): exec(compile(tree,"<oracle>","exec"),env,env)
    except _Budget as e: print(str(e),file=out)
    except BaseException as e: print(type(e).__name__+": "+str(e),file=out)
    finally: sys.settrace(old)
    value=out.getvalue()
    return value[:16000] if value else "Execution completed (no output)."
