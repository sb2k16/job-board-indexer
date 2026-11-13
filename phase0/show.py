import json,sys
d=json.load(open(sys.argv[1]+"/results.json"))
for r in d["results"]:
    s=f'{r["platform"][:14]:14} {str(r["tenant"])[:22]:22} '
    if not r.get("robots_ok"): print(s,"ROBOTS-DISALLOW",r.get("robots_status")); continue
    if "error" in r: print(s,"ERR",r["error"][:90]); continue
    x=f'{r.get("st")} {r.get("kb")}kb wire{r.get("wire_kb")} {r.get("kind")}'
    if r.get("kind")=="json": x+=f' n={r.get("n")} path={r.get("path")} keys={",".join(r.get("keys",[])[:14])}'
    if r.get("kind")=="xml": x+=f' items={r.get("items")} pos={r.get("positions")} ent={r.get("entries")} loc={r.get("urls")}'
    if r.get("kind")=="html": x+=f' ld={r.get("jsonld")} jp={r.get("jobposting")} links={r.get("job_links")} next={r.get("next_data")} blk={r.get("blocked")} txt={r.get("textlen")} t={r.get("title")!r}'
    if r.get("etag") or r.get("lm"): x+=f' VALID cond={r.get("cond")}'
    if r.get("delay"): x+=f' delay={r["delay"]}'
    if r.get("robots_status")!=200: x+=f' robots={r.get("robots_status")}'
    print(s,x[:300])
if len(sys.argv)>2:
    for k,v in d["robots"].items():
        if v.get("text"): print("\n##",k,"\n",v["text"][:500])
