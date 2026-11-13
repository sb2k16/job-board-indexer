#!/usr/bin/env python3
"""Phase 0 field validation: print the first job record's keys per Green platform from the saved samples."""
import glob,gzip,json,re,html,sys
def raw(pat):
    fs=sorted(glob.glob(f'samples/*/{pat}*.raw.gz')); 
    if not fs: return None
    return gzip.open(fs[-1]).read().decode('utf-8','replace')
def flat(o,p='',d=0,out=None):
    out=[] if out is None else out
    if isinstance(o,dict):
        for k,v in o.items(): flat(v,f'{p}.{k}' if p else k,d+1,out)
    elif isinstance(o,list):
        if o: flat(o[0],p+'[]',d+1,out)
        else: out.append((p,'[]'))
    else: out.append((p,str(o)[:60].replace('\n',' ')))
    return out
def show(name,rec,note=''):
    print(f"\n## {name} {note}")
    if rec is None: print("  (no record)"); return
    for k,v in flat(rec)[:70]: print(f"  {k} = {v}")
def getpath(j,path):
    for k in path.split('.'):
        if k=='[]': j=j[0]
        elif k.endswith('[]'): j=j[k[:-2]][0]
        elif k=='$': pass
        else: j=j[k]
    return j
def first_jsonld(t):
    for x in re.findall(r'<script[^>]*ld\+json[^>]*>(.*?)</script>',t,re.S|re.I):
        try: j=json.loads(x.strip())
        except Exception:
            try: j=json.loads(html.unescape(x.strip()))
            except Exception: continue
        cands=j if isinstance(j,list) else j.get('@graph',[j]) if isinstance(j,dict) else []
        for c in cands:
            if isinstance(c,dict) and 'JobPosting' in str(c.get('@type')): return c
    return None
def raw_decode_at(t,marker,start_char):
    i=t.find(marker)
    if i<0: return None
    i=t.find(start_char,i)
    try: return json.JSONDecoder().raw_decode(t[i:i+400000])[0]
    except Exception as e: return None
J=[("ADP","adp__cide5360e53","jobRequisitions[]"),("Ashby","ashby__openai","jobs[]"),("Breezy","breezy__vetsez","[]"),("Eightfold","eightfold__costar","positions[]"),("Gem","gem__firestorm","[]"),
("Greenhouse","greenhouse__stripe","jobs[]"),("HireHive","hirehive__irish","jobs[]"),("Lever","lever__palantir","[]"),("Oracle","oracle__edyo","items[].requisitionList[]"),("Pinpoint","pinpoint__indrive","data[]"),
("Recruitee","recruitee__bunq","offers[]"),("Workable","workable__tiger","jobs[]"),("Workday","workday__nvidia","jobPostings[]")]
for n,f,p in J:
    t=raw(f); 
    try: j=json.loads(t); rec=getpath(j,p); top=list(j)[:12] if isinstance(j,dict) else f'list[{len(j)}]'
    except Exception as e: rec=None; top=str(e)[:60]
    show(n,rec,f'(json {p}; top={top})')
X=[("Avature","avature__bloomberg-feed","item"),("HireBridge","hirebridge__rss6741","item"),("JobBoard.io","jobboard.io__hireheroes","item"),("Jobboardly","jobboardly__medicalcourierjobs","item"),("Madgex","madgex__insidehighered","item"),
("Personio","personio__desotec-xml","position"),("Teamtailor","teamtailor__vardaga","item"),("WP Job Manager","wpjobmanager__lathire","item"),("Paylocity","paylocity__xml27577",None),("JobKit","jobkit__rails-rss","item")]
for n,f,tag in X:
    t=raw(f)
    if t is None: print(f"\n## {n} (missing)"); continue
    if tag is None:
        m=re.search(r'<([A-Za-z]+)[^>]*>\s*<([A-Za-z]+)',t[t.find('?>')+2:]); 
        tag=re.findall(r'<([A-Za-z]+)[ >]',t)[1] if len(re.findall(r'<([A-Za-z]+)[ >]',t))>1 else 'item'
    print(f"\n## {n} (xml <{tag}>)")
    import xml.etree.ElementTree as ET
    try: root=ET.fromstring(t.encode('utf-8','replace'))
    except Exception as e: print("  parse error",str(e)[:80]); continue
    el=next((e for e in root.iter() if e.tag.split('}')[-1]==tag),None)
    if el is None: print("  (no element)"); continue
    for c in el:
        v=re.sub(r'<[^>]+>',' ',html.unescape(c.text or '')).strip().replace('\n',' ')
        sub=[g.tag.split('}')[-1] for g in c][:6]
        print(f"  {c.tag.split('}')[-1]}{(' '+str(c.attrib)) if c.attrib else ''} = {v[:70]}{(' children='+str(sub)) if sub else ''}")
L=[("ApplicantPro","applicantpro__opc-detail__"),("CareerPlug","careerplug__i9-sports"),("Deel","deel__airbase-detail"),("FirstStage","firststage__quantumbase-detail"),("Freshteam","freshteam__talent360-detail"),("GoHire","gohire__taisan"),
("Hireology","hireology__nextpoint-detail"),("HiringThing","hiringthing__oppco-detail"),("iCIMS","icims__actslife-detail"),("iSolvedHire","isolved__directviz-detail"),("JazzHR","jazzhr__fincons-detail"),("Jobvite","jobvite__egnyte-detail"),
("JOIN","join__join-detail"),("Kula","kula__avantstay-detail"),("Manatal","manatal__the-pss-group-detail"),("Recooty","recooty__gururo-detail"),("Trakstar","trakstar__bennington-detail"),("Taleo TBE","taleo__tbe-lde-detail"),
("JBoard","jboard__golf-jobs-detail"),("SmartJobBoard","smartjobboard__academiccareers-detail"),("jobBoardHQ","jobboardhq__jbcjobs-detail"),("JobBoard.io detail","jobboard.io__blooloop-detail")]
for n,f in L:
    t=raw(f); show(n+" JSON-LD", first_jsonld(t) if t else None)
# embedded
t=raw("comeet__cyera"); m=re.search(r'COMPANY_POSITIONS_DATA\s*=\s*(\[.*?\]);',t or '',re.S); 
try: show("Comeet embedded",json.loads(m.group(1))[0])
except Exception as e: show("Comeet embedded",None,str(e)[:80])
t=raw("phenom__cvshealth__"); j=raw_decode_at(t or '','"jobs":[','['); show("Phenom embedded",j[0] if j else None)
t=raw("rippling__nocd__"); 
for mk in ('"jobPosts"','"items":[{','job-posts'):
    j=raw_decode_at(t or '',mk,'[')
    if j and isinstance(j,list) and j and isinstance(j[0],dict): show("Rippling embedded",j[0],mk); break
else: show("Rippling embedded",None,"markers not found; __NEXT_DATA__ present=%s"%('__NEXT_DATA__' in (t or '')))
t=raw("jobiqo__miamijobs-search"); m=re.search(r'<script id="__NEXT_DATA__"[^>]*>(.*?)</script>',t or '',re.S)
if m:
    j=json.loads(m.group(1)); best=(0,None)
    def walk(o):
        global best
        if isinstance(o,list) and o and all(isinstance(x,dict) for x in o[:3]) and len(o)>best[0] and any(k in o[0] for k in ('title','name','jobTitle')): best=(len(o),o[0])
        if isinstance(o,dict): [walk(v) for v in o.values()]
        elif isinstance(o,list): [walk(v) for v in o]
    walk(j); show("Jobiqo embedded",best[1],f'n={best[0]}')
else: show("Jobiqo embedded",None)
t=raw("zoho__jamppharma__"); m=re.search(r'<input type="hidden" value="(\[\{&#34;Remote_Job.*?\])"',t or '',re.S) or re.search(r'value="(\[\{[^"]*Posting_Title[^"]*\])"',t or '')
try: show("Zoho embedded",json.loads(html.unescape(m.group(1)))[0])
except Exception as e: show("Zoho embedded",None,str(e)[:80])
