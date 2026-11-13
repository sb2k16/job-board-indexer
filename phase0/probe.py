#!/usr/bin/env python3
"""Phase 0 feasibility probe. Stdlib only. Usage: probe.py targets.json outdir
Honours robots.txt, spaces requests per host, sends one conditional re-request per platform."""
import gzip,hashlib,io,json,os,re,sys,time,urllib.error,urllib.parse,urllib.request
UA="JobBoardIndexer/0.1 (feasibility probe; low volume)"
DELAY=2.0; MAXB=40*1024*1024; TO=30
class NoErr(urllib.request.HTTPErrorProcessor):
    def http_response(self,req,resp): return resp
    https_response=http_response
last={}; robots={}
def fetch(url,extra=None,method="GET",body=None,hops=0):
    h=urllib.parse.urlsplit(url).netloc
    w=DELAY-(time.time()-last.get(h,0))
    if w>0: time.sleep(w)
    hdr={"User-Agent":UA,"Accept":"*/*","Accept-Encoding":"gzip"}; hdr.update(extra or {})
    req=urllib.request.Request(url,headers=hdr,method=method,data=body)
    try:
        r=urllib.request.urlopen(req,timeout=TO)
        raw=r.read(MAXB+1); st=r.status; hs=r.headers; final=r.geturl()
    except urllib.error.HTTPError as e:
        raw=e.read(MAXB+1); st=e.code; hs=e.headers; final=url
    except Exception as e:
        last[h]=time.time(); return {"error":f"{type(e).__name__}: {str(e)[:150]}"}
    last[h]=time.time()
    if st==308 and hops<4 and hs.get("Location"):
        return fetch(urllib.parse.urljoin(url,hs["Location"]),extra,method,body,hops+1)
    wire=len(raw); trunc=wire>MAXB
    if hs.get("Content-Encoding","")=="gzip" and not trunc:
        try: raw=gzip.decompress(raw)
        except Exception: pass
    return {"st":st,"raw":raw,"ct":hs.get("Content-Type",""),"etag":hs.get("ETag"),"lm":hs.get("Last-Modified"),"final":final,"wire":wire,"trunc":trunc,"server":hs.get("Server",""),"retry":hs.get("Retry-After")}
class Robots:
    """RFC 9309: use the group naming our product token, else '*'; within it the longest matching rule wins; ties go to allow."""
    def __init__(self,txt,token):
        groups=[]; cur=None; last_ua=False
        for line in txt.splitlines():
            line=line.split("#",1)[0].strip()
            if ":" not in line: continue
            k,v=[x.strip() for x in line.split(":",1)]; k=k.lower()
            if k=="user-agent":
                if not last_ua: cur={"ua":[],"rules":[],"delay":None}; groups.append(cur)
                cur["ua"].append(v.lower()); last_ua=True; continue
            last_ua=False
            if cur is None: continue
            if k in ("allow","disallow"): cur["rules"].append((k=="allow",v))
            elif k=="crawl-delay":
                try: cur["delay"]=float(v)
                except ValueError: pass
        t=token.lower()
        mine=[g for g in groups if any(u!="*" and u in t for u in g["ua"])]
        sel=mine or [g for g in groups if "*" in g["ua"]]
        self.rules=[r for g in sel for r in g["rules"]]
        self.delay=next((g["delay"] for g in sel if g["delay"] is not None),None)
    @staticmethod
    def _rx(pat):
        end=pat.endswith("$"); pat=pat[:-1] if end else pat
        return re.compile("".join(".*" if c=="*" else re.escape(c) for c in pat)+("$" if end else ""))
    def allowed(self,path):
        best=None
        for allow,pat in self.rules:
            if pat=="": continue
            if self._rx(pat).match(path):
                L=len(pat)
                if best is None or L>best[0] or (L==best[0] and allow): best=(L,allow)
        return True if best is None else best[1]
def robot(url):
    s=urllib.parse.urlsplit(url); base=f"{s.scheme}://{s.netloc}"
    if base not in robots:
        r=fetch(base+"/robots.txt"); rp=None
        info={"status":r.get("st"),"error":r.get("error")}
        if r.get("st")==200:
            txt=r["raw"].decode("utf-8","replace"); rp=Robots(txt,"JobBoardIndexer"); info["text"]=txt[:4000]; mode="parse"
        elif r.get("st") and 400<=r["st"]<500: mode="allow"
        else: mode="deny"
        robots[base]=(rp,mode,info)
    rp,mode,info=robots[base]
    path=(s.path or "/")+("?"+s.query if s.query else "")
    ok=True if mode=="allow" else False if mode=="deny" else rp.allowed(path)
    d=rp.delay if mode=="parse" else None
    return ok,info,d
LISTK=("jobs","offers","postings","content","results","items","data","job_posts","positions","openings")
def longest(o,depth=0):
    best=(0,None,None)
    if isinstance(o,list):
        if o and all(isinstance(x,dict) for x in o[:5]): best=(len(o),"$",o[0])
        for x in o[:3]:
            if depth<4:
                b=longest(x,depth+1)
                if b[0]>best[0]: best=b
    elif isinstance(o,dict) and depth<4:
        for k,v in o.items():
            b=longest(v,depth+1)
            if b[0]>best[0] or (b[0]==best[0] and b[0] and k in LISTK): best=(b[0],k if b[1]=="$" else f"{k}.{b[1]}",b[2])
    return best
def analyse(raw,ct):
    a={}; t=raw[:MAXB].decode("utf-8","replace"); head=t.lstrip()[:200].lower()
    if "json" in ct or head[:1] in "[{":
        try:
            j=json.loads(t); n,path,first=longest(j); a.update(kind="json",n=n,path=path,keys=sorted(first)[:40] if first else [])
            if isinstance(j,dict): a["top"]=list(j)[:15]
            return a
        except Exception as e: a["json_err"]=str(e)[:80]
    if "<rss" in head or "<?xml" in head or "xml" in ct:
        a.update(kind="xml",items=len(re.findall(r"<item[ >]",t)),positions=len(re.findall(r"<position[ >]",t)),entries=len(re.findall(r"<entry[ >]",t)),urls=len(re.findall(r"<loc>",t)),tags=sorted(set(re.findall(r"<([a-zA-Z:_]+)[ >/]",t)))[:30]); return a
    ld=re.findall(r'<script[^>]*application/ld\+json[^>]*>(.*?)</script>',t,re.S|re.I)
    a.update(kind="html",jsonld=len(ld),jobposting=sum(x.count("JobPosting") for x in ld),title=(re.search(r"<title[^>]*>(.*?)</title>",t,re.S|re.I) or [None,""])[1].strip()[:90],
      next_data="__NEXT_DATA__" in t,job_links=len(set(re.findall(r'href="([^"]*(?:/jobs?/|/job-|/o/|/positions?/|/apply/|/careers?/)[^"]*)"',t))),
      blocked=[w for w in ("captcha","cf-chl","just a moment","access denied","attention required","enable javascript") if w in t.lower()][:4],textlen=len(re.sub(r"<[^>]+>"," ",t)))
    return a
def main():
    tg=json.load(open(sys.argv[1])); out=sys.argv[2]; os.makedirs(out,exist_ok=True); res=[]; cond={}
    for x in tg:
        u=x["url"]; rec={k:x.get(k) for k in("platform","tenant","url")}
        ok,info,d=robot(u); rec.update(robots_ok=ok,robots_status=info["status"],delay=d)
        if info.get("error"): rec["robots_err"]=info["error"]
        if not ok: res.append(rec); continue
        if x.get("sleep"): time.sleep(float(x["sleep"]))
        b=x.get("body"); r=fetch(u,x.get("headers"),x.get("method","GET"),b.encode() if b else None)
        if "error" in r: rec["error"]=r["error"]; res.append(rec); continue
        rec.update(st=r["st"],ct=r["ct"][:40],kb=round(len(r["raw"])/1024,1),wire_kb=round(r["wire"]/1024,1),etag=bool(r["etag"]),lm=bool(r["lm"]),final=r["final"][:120],server=r["server"][:25],trunc=r["trunc"])
        if r["retry"]: rec["retry_after"]=r["retry"]
        rec.update(analyse(r["raw"],r["ct"]))
        n=re.sub(r"[^A-Za-z0-9_.-]+","_",f'{x["platform"]}__{x.get("tenant") or "x"}__{hashlib.md5(u.encode()).hexdigest()[:6]}')
        with gzip.open(os.path.join(out,n+".raw.gz"),"wb") as f: f.write(r["raw"])
        rec["sample"]=n+".raw.gz"
        if r["st"]==200 and (r["etag"] or r["lm"]) and x["platform"] not in cond:
            h={}
            if r["etag"]: h["If-None-Match"]=r["etag"]
            if r["lm"]: h["If-Modified-Since"]=r["lm"]
            c=fetch(u,h); cond[x["platform"]]=c.get("st",c.get("error")); rec["cond"]=cond[x["platform"]]
        res.append(rec)
    json.dump({"results":res,"robots":{k:v[2] for k,v in robots.items()}},open(os.path.join(out,"results.json"),"w"),indent=1)
main()
