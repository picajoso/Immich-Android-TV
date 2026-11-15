import re,glob,sys,os
sys.path.insert(0,os.path.dirname(__file__))
from es import ES
ROOT='/Users/javipas/Immich-Android-TV-redesign/app/src/main/'
def key(t): return 't_'+re.sub(r'[^a-z0-9]+','_',t.lower()).strip('_')[:60]
def unesc(s): return s.replace('\\n','\n').replace('\\"','"').replace("\\'","'").replace('\\$','$').replace('\\\\','\\')
def literals(src):
    out=[];i=0;n=len(src)
    while i<n:
        c=src[i]
        if src.startswith('//',i): i=src.find('\n',i); i=n if i<0 else i; continue
        if src.startswith('/*',i): i=src.find('*/',i)+2; continue
        if c=="'": i+= 4 if src[i+1]=='\\' else 3; continue
        if c=='"':
            j=i+1;buf=''
            while src[j]!='"':
                if src[j]=='\\': buf+=src[j:j+2]; j+=2
                else: buf+=src[j]; j+=1
            out.append(buf); i=j+1; continue
        i+=1
    return out
texts={}
def add(t):
    t=unesc(t)
    if t.strip():
        k=key(t)
        if k in texts and texts[k]!=t: print('COLLISION',repr(texts[k]),repr(t))
        texts.setdefault(k,t)
# 1) Tr.t("...") calls
for f in glob.glob(ROOT+'java/**/*.kt',recursive=True):
    s=open(f).read()
    for m in re.finditer(r'Tr\.t\("((?:[^"\\]|\\.)*)"\)',s): add(m.group(1))
# 2) Preferences.kt literals
s=open(ROOT+'java/nl/giejay/android/tv/immich/shared/prefs/Preferences.kt').read()
for l in literals(s):
    if re.fullmatch(r'[A-Z][a-zA-Z ]+',l) or (re.search(r'[A-Za-z]{3}',l) and not re.fullmatch(r'[a-zA-Z0-9_.:/-]+',l) and '$' not in l):
        if l not in ('Google',): add(l)
# 3) extras
for t in ["Photos","Albums","People","Favorites","Random","On this day","Timeline","Edit","Settings","Library","Collections","%d photo","%d photos","%d person","%d people","1 year ago","%d years ago","Done","Recent","Seasonal","Folders","Recently added","Recently taken"]: add(t)
# 4) xml texts (and rewrite them to @string)
xml_files=glob.glob(ROOT+'res/layout/*.xml')+glob.glob(ROOT+'res/xml/*.xml')
SKIP={'2024','Abril','(123)','EMPTY'}
for f in xml_files:
    s=open(f).read(); changed=False
    def rep(m):
        global changed
        v=m.group(2)
        if v in SKIP or re.fullmatch(r'[0-9() ]+',v): return m.group(0)
        if v=='Foto de la línea de tiempo': v2='Timeline photo'
        else: v2=v
        add(v2.replace('\n','\\n') if False else v2)
        return 'android:%s="@string/%s"'%(m.group(1),key(unesc(v2)))
    s2=re.sub(r'android:(text|title|summary|hint|contentDescription|dialogTitle)="([^@"][^"]*)"',rep,s)
    if s2!=s and '--write' in sys.argv: open(f,'w').write(s2)
for t in ES: add(t)
missing=[t for k,t in texts.items() if t not in ES]
if missing:
    print('MISSING',len(missing))
    for t in missing: print(repr(t))
def x(t): 
    t=t.replace('&','&amp;').replace('<','&lt;').replace("'","\\'").replace('"','\\"').replace('\n','\\n')
    if t.startswith('@') or t.startswith('?'): t='\\'+t
    return t
def write(path,items):
    os.makedirs(os.path.dirname(path),exist_ok=True)
    with open(path,'w') as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated from the Tr.t("...") texts of the code; see shared/util/Tr.kt -->\n<resources>\n')
        for k,t in sorted(items.items()): f.write('    <string name="%s" formatted="false">%s</string>\n'%(k,x(t)))
        f.write('</resources>\n')
if '--write' in sys.argv and not missing:
    write(ROOT+'res/values/strings_tr.xml',texts)
    write(ROOT+'res/values-es/strings_tr.xml',{k:ES[t] for k,t in texts.items()})
    print('written',len(texts))
print(len(texts),'texts')
