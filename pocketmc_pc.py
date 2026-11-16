# PocketMC PC - Windows surumu (Python 3.9+, ek paket gerekmez)
import os, sys, json, re, shutil, hashlib, threading, subprocess, zipfile, time, urllib.request, urllib.parse, queue

APP = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "PocketMC")
SRV = os.path.join(APP, "server")
PROF = os.path.join(SRV, "profiles")
CFG = os.path.join(APP, "config.json")
JAR = "fabric-server.jar"
BASE_MODS = ["fabric-api", "lithium", "ferrite-core", "krypton", "scalablelux"]
CACHE_DIRS = [".fabric/remappedJars", ".fabric/server", "versions"]
UA = {"User-Agent": "PocketMC-PC/1.0"}
CF = 0x08000000 if os.name == "nt" else 0  # CREATE_NO_WINDOW


# ---------------- ayar ----------------
def cfg():
    d = {"mc": "", "world": "world", "ram": 2048, "owner": "", "java": ""}
    try:
        with open(CFG, encoding="utf-8") as f: d.update(json.load(f))
    except Exception: pass
    return d

def save_cfg(d):
    os.makedirs(APP, exist_ok=True)
    tmp = CFG + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f: json.dump(d, f)
    os.replace(tmp, CFG)


# ---------------- ag ----------------
def http(url, timeout=30):
    return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout)

def jget(url):
    with http(url) as r: return json.loads(r.read().decode("utf-8"))

def download(url, dest, cb=None):
    tmp = dest + ".part"
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with http(url, 60) as r, open(tmp, "wb") as f:
        tot = int(r.headers.get("Content-Length") or 0); got = 0
        while True:
            b = r.read(1 << 16)
            if not b: break
            f.write(b); got += len(b)
            if cb and tot: cb(got / tot)
    os.replace(tmp, dest)

def game_versions():
    return [v["version"] for v in jget("https://meta.fabricmc.net/v2/versions/game") if v.get("stable")]

def fabric_pair(mc):
    l = jget("https://meta.fabricmc.net/v2/versions/loader/" + mc)
    if not l: raise RuntimeError(mc + " icin Fabric yok")
    ins = jget("https://meta.fabricmc.net/v2/versions/installer")
    return l[0]["loader"]["version"], ins[0]["version"]


# ---------------- java ----------------
def find_java():
    c = cfg().get("java")
    cands = [c] if c else []
    jh = os.environ.get("JAVA_HOME")
    if jh: cands.append(os.path.join(jh, "bin", "java.exe" if os.name == "nt" else "java"))
    cands.append("java")
    for pf in (os.environ.get("ProgramFiles"), os.environ.get("ProgramFiles(x86)")):
        if not pf: continue
        for vend in ("Eclipse Adoptium", "Java", "Microsoft", "Zulu", "BellSoft"):
            base = os.path.join(pf, vend)
            if os.path.isdir(base):
                for d in sorted(os.listdir(base), reverse=True):
                    cands.append(os.path.join(base, d, "bin", "java.exe"))
    for j in cands:
        if j and (j == "java" or os.path.isfile(j)):
            m = java_major(j)
            if m: return j, m
    return None, 0

def java_major(j):
    try:
        out = subprocess.run([j, "-version"], capture_output=True, text=True, timeout=15, creationflags=CF)
        t = out.stderr + out.stdout
        m = re.search(r'version "([^"]+)"', t)
        if not m: return 0
        p = re.split(r"[._\-+]", m.group(1))
        return int(p[1]) if p[0] == "1" else int(p[0])
    except Exception: return 0

def need_java(mc):
    p = [int(x) for x in re.findall(r"\d+", mc)]
    if not p: return 17
    if p[0] >= 26: return 25
    minor = p[1] if len(p) > 1 else 0; patch = p[2] if len(p) > 2 else 0
    return 21 if (minor > 20 or (minor == 20 and patch >= 5)) else 17


# ---------------- dosya yardimcilari ----------------
def dsize(p):
    if os.path.isfile(p): return os.path.getsize(p)
    t = 0
    for r, _, fs in os.walk(p):
        for f in fs:
            try: t += os.path.getsize(os.path.join(r, f))
            except OSError: pass
    return t

def mb(b): return f"{b // 1048576} MB" if b >= 1048576 else f"{b // 1024} KB"

def worlds():
    c = cfg(); a = c["world"]
    l = [n for n in (os.listdir(SRV) if os.path.isdir(SRV) else [])
         if os.path.isdir(os.path.join(SRV, n)) and (n == "world" or n.startswith("w_"))]
    if a not in l: l.append(a)
    return sorted(l, key=lambda n: (n != a, n))

def wlabel(f): return "world (ana dunya)" if f == "world" else f[2:] if f.startswith("w_") else f

def ver_of(name):
    m = re.search(r"\d+\.\d+(?:\.\d+)?", name); return m.group(0) if m else None

def mv(s, d, journal=None):
    os.makedirs(os.path.dirname(d), exist_ok=True)
    os.replace(s, d) if not os.path.isdir(s) else os.rename(s, d)
    if journal is not None: journal.append((s, d))


# ---------------- profiller ----------------
def profiles():
    out = []
    if not os.path.isdir(PROF): return out
    for v in os.listdir(PROF):
        d = os.path.join(PROF, v)
        if not os.path.isdir(d): continue
        ws = os.listdir(os.path.join(d, "worlds")) if os.path.isdir(os.path.join(d, "worlds")) else []
        mods = [m for m in os.listdir(os.path.join(d, "mods")) if m.endswith(".jar")] if os.path.isdir(os.path.join(d, "mods")) else []
        out.append(dict(ver=v, mods=len(mods), worlds=ws, size=dsize(d), offline=os.path.isfile(os.path.join(d, JAR))))
    out.sort(key=lambda p: [int(x) for x in re.findall(r"\d+", p["ver"])], reverse=True)
    return out

def install_base_mods(mc, log, prog=None):
    md = os.path.join(SRV, "mods"); os.makedirs(md, exist_ok=True)
    for i, slug in enumerate(BASE_MODS):
        try:
            q = urllib.parse.quote('["fabric"]') ; g = urllib.parse.quote('["%s"]' % mc)
            arr = jget(f"https://api.modrinth.com/v2/project/{slug}/version?loaders={q}&game_versions={g}")
            if not arr: log(f"{slug}: {mc} icin surum yok, atlandi"); continue
            v = next((x for x in arr if x.get("version_type") == "release"), arr[0])
            f = next((x for x in v["files"] if x.get("primary")), v["files"][0])
            dest = os.path.join(md, os.path.basename(f["filename"]))
            download(f["url"], dest)
            want = (f.get("hashes") or {}).get("sha1", "")
            if want:
                h = hashlib.sha1(open(dest, "rb").read()).hexdigest()
                if h != want.lower(): os.remove(dest); raise RuntimeError("sha1 uyusmuyor")
            log("Mod kuruldu: " + os.path.basename(dest))
        except Exception as e: log(f"{slug} kurulamadi: {e}")
        if prog: prog((i + 1) / len(BASE_MODS))

def install_first(mc, base_mods, log, prog=None):
    """Ilk kurulum: sunucu jar'i indirir, EULA dosyasi eklemez (arayuz sorar)."""
    os.makedirs(SRV, exist_ok=True)
    loader, inst = fabric_pair(mc)
    log("Fabric sunucusu indiriliyor...")
    new = os.path.join(SRV, JAR + ".new")
    download(f"https://meta.fabricmc.net/v2/versions/loader/{mc}/{loader}/{inst}/server/jar", new, prog)
    if os.path.getsize(new) < 1024: os.remove(new); raise RuntimeError("Indirilemedi")
    os.replace(new, os.path.join(SRV, JAR))
    c = cfg(); c["mc"] = mc; save_cfg(c)
    if base_mods: install_base_mods(mc, log)

def switch_version(target, base_mods, log, prog=None):
    c = cfg(); old = c["mc"]
    if not old: return install_first(target, base_mods, log, prog)
    if target == old: return
    need_dl = not os.path.isfile(os.path.join(PROF, target, JAR))
    new = os.path.join(SRV, JAR + ".new")
    journal = []
    try:
        if need_dl:
            loader, inst = fabric_pair(target); log("Fabric sunucusu indiriliyor...")
            download(f"https://meta.fabricmc.net/v2/versions/loader/{target}/{loader}/{inst}/server/jar", new, prog)
            if os.path.getsize(new) < 1024: raise RuntimeError("Sunucu dosyasi indirilemedi")
        else: log("Kayitli surum kullaniliyor (internet gerekmez)")
        oldp = os.path.join(PROF, old); newp = os.path.join(PROF, target)
        mods = os.path.join(SRV, "mods")
        if os.path.isdir(mods): mv(mods, os.path.join(oldp, "mods"), journal)
        for w in worlds():
            p = os.path.join(SRV, w)
            if os.path.isdir(p): mv(p, os.path.join(oldp, "worlds", w), journal)
        jar = os.path.join(SRV, JAR)
        if os.path.isfile(jar): mv(jar, os.path.join(oldp, JAR), journal)
        for rel in CACHE_DIRS:
            d = os.path.join(SRV, *rel.split("/"))
            if os.path.isdir(d):
                for n in os.listdir(d):
                    if ver_of(n) == old: mv(os.path.join(d, n), os.path.join(oldp, "cache", *rel.split("/"), n), journal)
        had_mods = os.path.isdir(os.path.join(newp, "mods"))
        if had_mods: mv(os.path.join(newp, "mods"), mods, journal)
        nw = os.path.join(newp, "worlds")
        if os.path.isdir(nw):
            for n in os.listdir(nw): mv(os.path.join(nw, n), os.path.join(SRV, n), journal)
        if not need_dl: mv(os.path.join(newp, JAR), jar, journal)
        for rel in CACHE_DIRS:
            d = os.path.join(newp, "cache", *rel.split("/"))
            if os.path.isdir(d):
                for n in os.listdir(d): mv(os.path.join(d, n), os.path.join(SRV, *rel.split("/"), n), journal)
        os.makedirs(mods, exist_ok=True)
        if need_dl: os.replace(new, jar)
        c["world_" + old] = c["world"]; c["world"] = c.get("world_" + target, "world"); c["mc"] = target
        save_cfg(c)
    except BaseException:
        if os.path.exists(new): os.remove(new)
        for s, d in reversed(journal):
            try:
                if os.path.exists(d) and not os.path.exists(s): mv(d, s)
            except Exception as e: log("Geri alma hatasi: %s" % e)
        raise
    shutil.rmtree(os.path.join(PROF, target), ignore_errors=True)
    if base_mods and not had_mods: install_base_mods(target, log)

def export_profile_worlds(ver, outdir, log):
    """Saklanan surumun dunyalarini zip yapip dogrular; basarili ise True."""
    wd = os.path.join(PROF, ver, "worlds")
    for w in (os.listdir(wd) if os.path.isdir(wd) else []):
        zip_dir(os.path.join(wd, w), os.path.join(outdir, f"{ver}-{wlabel(w)}.zip"), wlabel(w))
        log(f"Cikarildi: {ver}-{wlabel(w)}.zip")
    return True

def delete_profile(ver, outdir, log):
    c = cfg()
    if ver == c["mc"]: raise RuntimeError("Aktif surum silinemez")
    wd = os.path.join(PROF, ver, "worlds")
    if os.path.isdir(wd) and os.listdir(wd):
        if not outdir: raise RuntimeError("Cikarma klasoru secilmedi")
        export_profile_worlds(ver, outdir, log)
    shutil.rmtree(os.path.join(PROF, ver))

def zip_dir(src, dest, prefix):
    tmp = dest + ".part"
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
        for r, _, fs in os.walk(src):
            for f in fs:
                p = os.path.join(r, f)
                if f == "session.lock": continue
                z.write(p, os.path.join(prefix, os.path.relpath(p, src)))
    with zipfile.ZipFile(tmp) as z:  # dogrulama: tamamen oku
        bad = z.testzip()
        if bad: raise RuntimeError("Zip bozuk: " + bad)
        if os.path.exists(os.path.join(src, "level.dat")) and not any(n.endswith("level.dat") for n in z.namelist()):
            raise RuntimeError("level.dat zipte yok")
    os.replace(tmp, dest)


# ---------------- dunya islemleri ----------------
def rename_world(old, name):
    c = cfg()
    if not re.fullmatch(r"[A-Za-z0-9_]{1,16}", name) or name == "world": return "Ad: harf, rakam, _ (1-16); 'world' olamaz"
    nf = "w_" + name
    if nf == old: return None
    if os.path.exists(os.path.join(SRV, nf)): return "Bu adda dunya var"
    s = os.path.join(SRV, old)
    if os.path.isdir(s): os.rename(s, os.path.join(SRV, nf))
    if c["world"] == old: c["world"] = nf; save_cfg(c)
    return None

def new_world(name):
    if not re.fullmatch(r"[A-Za-z0-9_]{1,16}", name) or name == "world": return "Ad: harf, rakam, _ (1-16)"
    if os.path.exists(os.path.join(SRV, "w_" + name)): return "Bu adda dunya var"
    os.makedirs(os.path.join(SRV, "w_" + name)); return None

def delete_world(f):
    shutil.rmtree(os.path.join(SRV, f), ignore_errors=True)
    c = cfg()
    if c["world"] == f: c["world"] = "world"; save_cfg(c)

def set_prop(key, val):
    p = os.path.join(SRV, "server.properties")
    lines = open(p, encoding="utf-8").read().splitlines() if os.path.exists(p) else []
    for i, l in enumerate(lines):
        if l.startswith(key + "="): lines[i] = f"{key}={val}"; break
    else: lines.append(f"{key}={val}")
    open(p, "w", encoding="utf-8").write("\n".join(lines) + "\n")

def get_prop(key, default=""):
    p = os.path.join(SRV, "server.properties")
    if os.path.exists(p):
        for l in open(p, encoding="utf-8").read().splitlines():
            if l.startswith(key + "="): return l.split("=", 1)[1]
    return default

def clear_cache():
    for rel in [".fabric", "versions", "libraries", "logs", "crash-reports", "cache"]:
        shutil.rmtree(os.path.join(SRV, rel), ignore_errors=True)


# ---------------- sunucu sureci ----------------
class Server:
    def __init__(self, on_line, on_state):
        self.p = None; self.on_line = on_line; self.on_state = on_state

    @property
    def running(self): return self.p is not None and self.p.poll() is None

    def start(self):
        c = cfg()
        if self.running: return
        java, jm = find_java()
        if not java: raise RuntimeError("Java bulunamadi. Adoptium Temurin %d+ kurun." % need_java(c["mc"]))
        if jm < need_java(c["mc"]): raise RuntimeError("%s icin Java %d gerekir, kurulu Java %d" % (c["mc"], need_java(c["mc"]), jm))
        if not os.path.isfile(os.path.join(SRV, JAR)): raise RuntimeError("Sunucu kurulu degil")
        with open(os.path.join(SRV, "eula.txt"), "w") as f: f.write("eula=true\n")
        set_prop("level-name", c["world"])
        ram = int(c["ram"])
        self.p = subprocess.Popen([java, "-Xms256M", "-Xmx%dM" % ram, "-jar", JAR, "nogui"], cwd=SRV, stdin=subprocess.PIPE,
                                  stdout=subprocess.PIPE, stderr=subprocess.STDOUT, creationflags=CF, text=True, encoding="utf-8", errors="replace", bufsize=1)
        self.on_state(True)
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        p = self.p
        for l in p.stdout: self.on_line(l.rstrip("\n"))
        p.wait(); self.on_state(False)

    def send(self, cmd):
        if self.running:
            try: self.p.stdin.write(cmd + "\n"); self.p.stdin.flush()
            except Exception: pass

    def stop(self):
        self.send("stop")


# ---------------- arayuz ----------------
def main():
    import tkinter as tk
    from tkinter import ttk, messagebox, simpledialog, filedialog
    BG, PANEL, FG, MUTED, ACC, RED = "#0f1815", "#17231f", "#e8f0ec", "#8fa098", "#72df91", "#ff8a83"
    root = tk.Tk(); root.title("PocketMC PC"); root.geometry("980x620"); root.configure(bg=BG)
    st = ttk.Style(); st.theme_use("clam")
    st.configure(".", background=BG, foreground=FG, fieldbackground=PANEL, bordercolor="#2b3a34")
    st.configure("TNotebook", background=BG); st.configure("TNotebook.Tab", background=PANEL, foreground=FG, padding=(14, 6))
    st.map("TNotebook.Tab", background=[("selected", "#1f3b2c")], foreground=[("selected", ACC)])
    st.configure("TButton", background=PANEL, foreground=FG, padding=6); st.map("TButton", background=[("active", "#1f3b2c")])
    st.configure("Acc.TButton", background=ACC, foreground="#0d2b1a"); st.configure("TLabel", background=BG, foreground=FG)
    st.configure("TFrame", background=BG); st.configure("TCheckbutton", background=BG, foreground=FG)
    st.configure("Treeview", background=PANEL, fieldbackground=PANEL, foreground=FG, rowheight=26)
    st.configure("Horizontal.TProgressbar", background=ACC, troughcolor=PANEL)

    q = queue.Queue()
    def log(s): q.put(("log", s))
    srv = Server(lambda l: q.put(("line", l)), lambda r: q.put(("state", r)))
    nb = ttk.Notebook(root); nb.pack(fill="both", expand=True, padx=8, pady=8)
    status = tk.StringVar(value="")
    bar = ttk.Progressbar(root, maximum=1.0); bar.pack(fill="x", padx=8)
    ttk.Label(root, textvariable=status, foreground=MUTED).pack(fill="x", padx=10, pady=(2, 6))
    busy = {"v": False}

    def run_bg(fn, ok_msg="", after=None):
        if busy["v"]: return
        busy["v"] = True
        def w():
            try: fn(); q.put(("done", ok_msg, after))
            except Exception as e: q.put(("err", str(e), None))
        threading.Thread(target=w, daemon=True).start()
    def prog(x): q.put(("prog", x))

    # --- Sunucu ---
    f1 = ttk.Frame(nb); nb.add(f1, text="Sunucu")
    top = ttk.Frame(f1); top.pack(fill="x", padx=8, pady=8)
    lbl = tk.StringVar(); ttk.Label(top, textvariable=lbl, font=("Segoe UI", 12, "bold")).pack(side="left")
    btn = ttk.Button(top, text="Baslat", style="Acc.TButton"); btn.pack(side="right")
    ttk.Label(top, text="RAM MB:").pack(side="right", padx=(0, 4))
    ram = tk.StringVar(value=str(cfg()["ram"])); ttk.Entry(top, textvariable=ram, width=6).pack(side="right", padx=4)
    con = tk.Text(f1, bg="#0b1210", fg=FG, insertbackground=FG, font=("Consolas", 10), state="disabled", wrap="none")
    con.pack(fill="both", expand=True, padx=8)
    con.tag_configure("w", foreground="#e0b341"); con.tag_configure("e", foreground=RED); con.tag_configure("a", foreground=ACC)
    cmd = tk.StringVar(); ce = ttk.Entry(f1, textvariable=cmd); ce.pack(fill="x", padx=8, pady=8)
    def put(l, tag=None):
        if tag is None: tag = "e" if ("ERROR" in l or "Exception" in l) else "w" if "WARN" in l else "a" if l.startswith("[PocketMC]") else None
        con.configure(state="normal"); con.insert("end", l + "\n", tag or ()); con.configure(state="disabled"); con.see("end")
        if int(con.index("end-1c").split(".")[0]) > 3000: con.configure(state="normal"); con.delete("1.0", "500.0"); con.configure(state="disabled")
    def sendcmd(_=None):
        if cmd.get().strip(): srv.send(cmd.get().strip()); cmd.set("")
    ce.bind("<Return>", sendcmd)
    def toggle():
        if srv.running: srv.stop(); return
        c = cfg()
        if not c["mc"]: messagebox.showinfo("PocketMC", "Once Surumler sekmesinden bir Minecraft surumu secin."); return
        if not os.path.exists(os.path.join(SRV, "eula.txt")) and not messagebox.askyesno("EULA", "Minecraft EULA'yi (https://aka.ms/MinecraftEULA) kabul ediyor musunuz?"): return
        try: c["ram"] = int(ram.get()); save_cfg(c); srv.start()
        except Exception as e: messagebox.showerror("PocketMC", str(e))
    btn.configure(command=toggle)

    # --- Surumler ---
    f2 = ttk.Frame(nb); nb.add(f2, text="Surumler")
    cur = tk.StringVar(); ttk.Label(f2, textvariable=cur, font=("Segoe UI", 12, "bold")).pack(anchor="w", padx=8, pady=(8, 2))
    jv = tk.StringVar(); ttk.Label(f2, textvariable=jv, foreground=MUTED).pack(anchor="w", padx=8)
    pl = ttk.Treeview(f2, columns=("d",), show="tree headings", height=5); pl.heading("#0", text="Saklanan surumler"); pl.heading("d", text="Icerik")
    pl.column("#0", width=140); pl.pack(fill="x", padx=8, pady=6)
    row = ttk.Frame(f2); row.pack(fill="x", padx=8)
    base = tk.BooleanVar(value=True); ttk.Checkbutton(row, text="Yeni surume temel modlari kur", variable=base).pack(side="left")
    ttk.Label(f2, text="Yeni surum (kirmizi: kurulu Java ile calismaz)").pack(anchor="w", padx=8, pady=(8, 2))
    av = tk.Listbox(f2, bg=PANEL, fg=FG, selectbackground="#1f3b2c", height=9, activestyle="none", exportselection=False); av.pack(fill="both", expand=True, padx=8)
    avl = []
    def refresh_versions():
        c = cfg(); j, jm = find_java()
        cur.set("Aktif: " + (c["mc"] or "yok")); jv.set(f"Java: {jm}" if jm else "Java bulunamadi (Adoptium Temurin kurun)")
        pl.delete(*pl.get_children())
        for p in profiles(): pl.insert("", "end", iid=p["ver"], text=p["ver"], values=(f'{p["mods"]} mod, {len(p["worlds"])} dunya, {mb(p["size"])}' + (", internetsiz" if p["offline"] else ""),))
        av.delete(0, "end")
        for v in avl:
            if v == c["mc"] or any(p["ver"] == v for p in profiles()): continue
            av.insert("end", v)
            if jm and jm < need_java(v): av.itemconfig("end", fg=RED)
    def load_av():
        def w():
            try: avl[:] = game_versions(); q.put(("refresh", "", None))
            except Exception as e: q.put(("log", "Surum listesi alinamadi: %s" % e))
        threading.Thread(target=w, daemon=True).start()
    def do_switch(v):
        if srv.running: messagebox.showinfo("PocketMC", "Once sunucuyu durdurun."); return
        j, jm = find_java()
        if jm and jm < need_java(v): messagebox.showerror("PocketMC", f"{v} icin Java {need_java(v)} gerekir (kurulu: {jm})"); return
        if cfg()["mc"] and not messagebox.askyesno("Surum degistir", f"{v} surumune gecilsin mi?\nMevcut surumun modlari ve dunyalari saklanir, geri donunce gorunur."): return
        run_bg(lambda: switch_version(v, base.get(), log, prog), f"Aktif surum: {v}", refresh_all)
    def go_new(): 
        s = av.curselection()
        if s: do_switch(av.get(s[0]))
    def go_saved():
        s = pl.selection()
        if s: do_switch(s[0])
    def del_saved():
        s = pl.selection()
        if not s: return
        v = s[0]; p = next(x for x in profiles() if x["ver"] == v); out = None
        if p["worlds"]:
            if not messagebox.askyesno("Sil", f"{v} modlari silinecek. {len(p['worlds'])} dunya once zip olarak cikarilacak. Devam?"): return
            out = filedialog.askdirectory(title="Dunyalarin cikarilacagi klasor", initialdir=os.path.join(os.path.expanduser("~"), "Downloads"))
            if not out: return
        elif not messagebox.askyesno("Sil", f"{v} silinsin mi?"): return
        run_bg(lambda: delete_profile(v, out, log), f"{v} silindi", refresh_all)
    r2 = ttk.Frame(f2); r2.pack(fill="x", padx=8, pady=8)
    ttk.Button(r2, text="Saklanana gec", command=go_saved).pack(side="left"); ttk.Button(r2, text="Sil", command=del_saved).pack(side="left", padx=6)
    ttk.Button(r2, text="Secili yeni surumu kur ve gec", style="Acc.TButton", command=go_new).pack(side="right")

    # --- Dunyalar ---
    f3 = ttk.Frame(nb); nb.add(f3, text="Dunyalar")
    wl = tk.Listbox(f3, bg=PANEL, fg=FG, selectbackground="#1f3b2c", activestyle="none", exportselection=False, font=("Segoe UI", 11)); wl.pack(fill="both", expand=True, padx=8, pady=8)
    def refresh_worlds():
        wl.delete(0, "end"); a = cfg()["world"]
        for w in worlds():
            d = os.path.join(SRV, w); wl.insert("end", f'{"* " if w == a else "  "}{wlabel(w)}   ({mb(dsize(d)) if os.path.isdir(d) else "yeni"})')
    def sel_w():
        s = wl.curselection(); return worlds()[s[0]] if s else None
    def act_world():
        w = sel_w()
        if w and not srv.running: c = cfg(); c["world"] = w; save_cfg(c); refresh_worlds()
        elif srv.running: messagebox.showinfo("PocketMC", "Once sunucuyu durdurun.")
    def ren_world():
        w = sel_w()
        if not w or (srv.running and w == cfg()["world"]): return
        n = simpledialog.askstring("Adi degistir", "Yeni ad (harf, rakam, _):")
        if n:
            e = rename_world(w, n.strip())
            if e: messagebox.showerror("PocketMC", e)
            refresh_worlds()
    def del_w():
        w = sel_w()
        if not w or (srv.running and w == cfg()["world"]): return
        if messagebox.askyesno("Sil", f"{wlabel(w)} kalici silinsin mi? Geri alinamaz."): delete_world(w); refresh_worlds()
    def exp_w():
        w = sel_w()
        if not w: return
        out = filedialog.asksaveasfilename(defaultextension=".zip", initialfile=wlabel(w) + ".zip")
        if out: run_bg(lambda: zip_dir(os.path.join(SRV, w), out, wlabel(w)), "Dunya zip olarak cikarildi")
    def new_w():
        n = simpledialog.askstring("Yeni dunya", "Ad (harf, rakam, _):")
        if n:
            e = new_world(n.strip())
            if e: messagebox.showerror("PocketMC", e)
            refresh_worlds()
    rb = ttk.Frame(f3); rb.pack(fill="x", padx=8, pady=(0, 8))
    for t, f in [("Aktif yap", act_world), ("Adi degistir", ren_world), ("Zip olarak cikar", exp_w), ("Sil", del_w), ("+ Bos dunya", new_w)]: ttk.Button(rb, text=t, command=f).pack(side="left", padx=3)

    # --- Modlar ---
    f4 = ttk.Frame(nb); nb.add(f4, text="Modlar")
    ml = tk.Listbox(f4, bg=PANEL, fg=FG, selectbackground="#1f3b2c", activestyle="none", selectmode="extended"); ml.pack(fill="both", expand=True, padx=8, pady=8)
    def refresh_mods():
        ml.delete(0, "end"); d = os.path.join(SRV, "mods")
        for m in sorted(os.listdir(d)) if os.path.isdir(d) else []: ml.insert("end", m)
    def del_mods():
        d = os.path.join(SRV, "mods")
        for i in ml.curselection(): os.remove(os.path.join(d, ml.get(i)))
        refresh_mods()
    def add_mods():
        fs = filedialog.askopenfilenames(filetypes=[("Mod", "*.jar")])
        os.makedirs(os.path.join(SRV, "mods"), exist_ok=True)
        for f in fs: shutil.copy(f, os.path.join(SRV, "mods"))
        refresh_mods()
    def base_now():
        c = cfg()
        if c["mc"]: run_bg(lambda: install_base_mods(c["mc"], log), "Temel modlar kuruldu", refresh_all)
    rb4 = ttk.Frame(f4); rb4.pack(fill="x", padx=8, pady=(0, 8))
    for t, f in [("Mod ekle (.jar)", add_mods), ("Secilenleri sil", del_mods), ("Temel modlari kur", base_now)]: ttk.Button(rb4, text=t, command=f).pack(side="left", padx=3)

    # --- Depolama ---
    f5 = ttk.Frame(nb); nb.add(f5, text="Depolama")
    sl = ttk.Treeview(f5, columns=("s",), show="tree headings", height=14); sl.heading("#0", text="Oge"); sl.heading("s", text="Boyut"); sl.column("#0", width=380)
    sl.pack(fill="both", expand=True, padx=8, pady=8)
    def refresh_storage():
        sl.delete(*sl.get_children()); c = cfg()
        sl.insert("", "end", iid="mods", text=f"Modlar ({c['mc']})", values=(mb(dsize(os.path.join(SRV, "mods"))),))
        for w in worlds():
            d = os.path.join(SRV, w)
            if os.path.isdir(d): sl.insert("", "end", iid="w:" + w, text="Dunya: " + wlabel(w), values=(mb(dsize(d)),))
        cs = sum(dsize(os.path.join(SRV, r)) for r in [".fabric", "versions", "libraries", "logs", "crash-reports", "cache"] if os.path.exists(os.path.join(SRV, r)))
        sl.insert("", "end", iid="cache", text="On bellek (yeniden indirilir)", values=(mb(cs),))
        for p in profiles(): sl.insert("", "end", iid="p:" + p["ver"], text=f'Surum {p["ver"]} (saklanan)', values=(mb(p["size"]),))
    def del_storage():
        s = sl.selection()
        if not s: return
        k = s[0]
        if srv.running: messagebox.showinfo("PocketMC", "Once sunucuyu durdurun."); return
        if k.startswith("p:"):
            v = k[2:]; p = next(x for x in profiles() if x["ver"] == v); out = None
            if p["worlds"]:
                out = filedialog.askdirectory(title="Dunyalar once buraya zip olarak cikarilacak")
                if not out: return
            elif not messagebox.askyesno("Sil", f"{v} silinsin mi?"): return
            run_bg(lambda: delete_profile(v, out, log), f"{v} silindi", refresh_all); return
        if not messagebox.askyesno("Sil", "Secili oge silinsin mi? Geri alinamaz."): return
        if k == "mods": shutil.rmtree(os.path.join(SRV, "mods"), ignore_errors=True); os.makedirs(os.path.join(SRV, "mods"), exist_ok=True)
        elif k == "cache": clear_cache()
        elif k.startswith("w:"): delete_world(k[2:])
        refresh_all()
    ttk.Button(f5, text="Secileni sil", command=del_storage).pack(anchor="e", padx=8, pady=(0, 8))
    ttk.Button(f5, text="Sunucu klasorunu ac", command=lambda: os.startfile(SRV) if os.name == "nt" else None).pack(anchor="e", padx=8, pady=(0, 8))

    def refresh_all():
        c = cfg(); lbl.set(f"Minecraft {c['mc'] or '-'} (Fabric)")
        btn.configure(text="Durdur" if srv.running else "Baslat")
        refresh_versions(); refresh_worlds(); refresh_mods(); refresh_storage()

    def pump():
        try:
            while True:
                m = q.get_nowait()
                if m[0] == "line": put(m[1])
                elif m[0] == "log": put("[PocketMC] " + m[1]); status.set(m[1])
                elif m[0] == "prog": bar["value"] = m[1]
                elif m[0] == "state": btn.configure(text="Durdur" if m[1] else "Baslat"); status.set("Calisiyor" if m[1] else "Kapali")
                elif m[0] == "refresh": refresh_versions()
                elif m[0] == "done":
                    busy["v"] = False; bar["value"] = 0; status.set(m[1] or "Tamam"); refresh_all()
                elif m[0] == "err":
                    busy["v"] = False; bar["value"] = 0; status.set("Hata"); messagebox.showerror("PocketMC", m[1] + "\n(Hicbir sey degismedi, her sey eski haline dondu)")
        except queue.Empty: pass
        root.after(100, pump)
    def on_close():
        if srv.running:
            if not messagebox.askyesno("PocketMC", "Sunucu calisiyor. Durdurulup kapatilsin mi?"): return
            srv.stop(); t0 = time.time()
            while srv.running and time.time() - t0 < 20: time.sleep(0.2)
        root.destroy()
    root.protocol("WM_DELETE_WINDOW", on_close)
    refresh_all(); load_av(); pump(); root.mainloop()

if __name__ == "__main__":
    main()
