#!/usr/bin/env python3
"""Patterns in the keyboard practice tap log (~/.local/share/oma-kb/taps.jsonl, from design/preview-server.py).

Boss types through mistakes, so each phrase's typed letters are lined up against the phrase (edit distance) to know
which letter every tap meant. Prints slips (meant -> got), how far taps land from each key's centre (dp, +x right,
+y down), and a check of touch-area fixes replayed on the real taps: a per-row shift and per-key learned offsets
(5-fold, fitted and tested on different taps). The last line is the per-key table used as AIM in
oma-keyboard-practice.html. Cover screen, wide board only for now.
"""
import json, collections, statistics as st
taps=[json.loads(l) for l in open('/root/.local/share/oma-kb/taps.jsonl')]
runs=[];cur=None
for t in taps:
    if cur is None or t['phrase'].lower()!=cur['phrase'] or (cur['taps'] and cur['taps'][-1]['got']=='⏎'):
        cur={'phrase':t['phrase'].lower(),'taps':[], 'v':t['keys']}; runs.append(cur)
    cur['taps'].append(t)
pairs=[]  # (intended, tap)
ins=collections.Counter(); dele=collections.Counter()
for r in runs:
    seq=[]
    for t in r['taps']:
        g=t['got']
        if g=='⌫':
            if seq: seq.pop()
        elif g=='⏎': pass
        elif len(g)==1: seq.append(t)
    a=[t['got'].lower() for t in seq]; b=r['phrase']
    n,m=len(a),len(b); D=[[0]*(m+1) for _ in range(n+1)]
    for i in range(n+1): D[i][0]=i
    for j in range(m+1): D[0][j]=j
    for i in range(1,n+1):
        for j in range(1,m+1):
            D[i][j]=min(D[i-1][j]+1,D[i][j-1]+1,D[i-1][j-1]+(a[i-1]!=b[j-1]))
    i,j=n,m
    while i or j:
        if i and j and D[i][j]==D[i-1][j-1]+(a[i-1]!=b[j-1]): pairs.append((b[j-1],seq[i-1],r['v'])); i-=1;j-=1
        elif i and D[i][j]==D[i-1][j]+1: ins[a[i-1]]+=1; i-=1
        else: dele[b[j-1]]+=1; j-=1
print('aligned taps',len(pairs),' extra taps',sum(ins.values()),' missed letters',sum(dele.values()), dict(dele))
sub=collections.Counter((w,t['got']) for w,t,v in pairs if w!=t['got'])
print('slips',sum(sub.values()),'of',len(pairs))
for (w,g),c in sub.most_common(40): print(f'  {w}->{g} {c}', end='')
print()
for v in ['flat','mech']:
    ps=[p for p in pairs if p[2]==v]; print(v, len(ps), 'slip%', round(100*sum(w!=t['got'] for w,t,_ in ps)/len(ps),1))
# directions of slips
ROWS=['qwertyuiop','asdfghjkl','zxcvbnm']
pos={}
for ri,r in enumerate(ROWS):
    for ci,c in enumerate(r): pos[c]=(ri, ci+ri*0.5 + (0.5 if ri==2 else 0))
dirs=collections.Counter()
for (w,g),c in sub.items():
    if w in pos and g in pos:
        dr=pos[g][0]-pos[w][0]; dc=pos[g][1]-pos[w][1]
        dirs[('down' if dr>0 else 'up' if dr<0 else 'same row')+'/'+('left' if dc<-0.1 else 'right' if dc>0.1 else '')]+=c
print('slip directions', dirs)
# offsets by key using all aligned taps with geometry of intended key from logged wx when want matched... recompute center: use hit key center of correct taps per key
cent={}
for w,t,v in pairs:
    if w==t['got']: cent.setdefault((w,v,t['sw']),(t['hx'],t['hy']))
off=collections.defaultdict(list)
for w,t,v in pairs:
    c=cent.get((w,v,t['sw']))
    if c and w!=' ': off[w].append((t['x']-c[0], t['y']-c[1]))
print('row offsets dx dy (dp), sd:')
for ri,r in enumerate(ROWS):
    L=[o for k in r for o in off[k]]
    print(f' row{ri+1} n={len(L)} dx={st.mean(o[0] for o in L):+.1f} dy={st.mean(o[1] for o in L):+.1f} sdx={st.pstdev(o[0] for o in L):.1f} sdy={st.pstdev(o[1] for o in L):.1f}')
    for k in r:
        if len(off[k])>=6:
            print(f'    {k} n={len(off[k])} dx={st.mean(o[0] for o in off[k]):+.1f} dy={st.mean(o[1] for o in off[k]):+.1f} slip={sum(1 for w,t,v in pairs if w==k and t["got"]!=k)}')
sp=[(t['x'],t['y']) for w,t,v in pairs if w==' ']
print('space taps x range', min(x for x,y in sp), max(x for x,y in sp), 'mean', st.mean(x for x,y in sp))
t0=taps[0]; print('geometry sw',t0['sw'])

# ---- Simulate touch-area corrections on the real taps (cover, wide). Same geometry as geom() in the page. ----
def keymap(mech):
    W=475; P=4; TOP=10 if mech else 6; H=54; u=(W-2*P)/10; rows=[]
    rows.append([(c,P+i*u,u) for i,c in enumerate(ROWS[0])])
    rows.append([(c,P+u/2+i*u,u) for i,c in enumerate(ROWS[1])])
    rows.append([('⇧',P,1.5*u)]+[(c,P+1.5*u+i*u,u) for i,c in enumerate(ROWS[2])]+[('⌫',P+8.5*u,1.5*u)])
    x=P; r3=[]
    for c,w in [('123',1.25),('emoji',1.1),(' ',4.4),('mic',1.5),('⏎',1.75)]: r3.append((c,x,w*u)); x+=w*u
    rows.append(r3); return rows,TOP,H
def hit(mech,x,y,shift=lambda r:(0,0)):
    rows,TOP,H=keymap(mech)
    r=max(0,min(3,int((y-TOP)//H)))
    dx,dy=shift(r); x+=dx; y+=dy
    r=max(0,min(3,int((y-TOP)//H)))
    best=min(rows[r],key=lambda k: 0 if k[1]<=x<k[1]+k[2] else min(abs(x-k[1]),abs(x-k[1]-k[2])))
    return best[0]
# sanity: replaying with no shift must reproduce what was typed
ok=sum(hit(v=='mech',t['x'],t['y'])==t['got'] for w,t,v in pairs); print('replay matches', ok, '/', len(pairs))
def rowof(c):
    for i,r in enumerate(ROWS):
        if c in r: return i
    return 3
def evaluate(fit_set, test_set):
    by=collections.defaultdict(list)
    for w,t,v in fit_set:
        c=cent.get((w,v,t['sw']))
        if c: by[rowof(w)].append((c[0]-t['x'], c[1]-t['y']))
    sh={r:(st.mean(a for a,b in L), st.mean(b for a,b in L)) for r,L in by.items()}
    def shift(r): return sh.get(r,(0,0))
    before=sum(hit(v=='mech',t['x'],t['y'])!=w for w,t,v in test_set)
    after=sum(hit(v=='mech',t['x'],t['y'],shift)!=w for w,t,v in test_set)
    return before, after, sh
ids=sorted({id(t) for _,t,_ in pairs}); 
A=[p for i,p in enumerate(pairs) if i%2==0]; B=[p for i,p in enumerate(pairs) if i%2==1]
b1,a1,_=evaluate(A,B); b2,a2,_=evaluate(B,A)
print(f'per-row shift, fit on half/test on other half: slips {b1+b2} -> {a1+a2} of {len(pairs)}')
_,_,sh=evaluate(pairs,pairs); print('per-row shift (dp, added to touch):', {r:(round(a,1),round(b,1)) for r,(a,b) in sh.items()})
for g in [(4,-4),(6,-6),(8,-6),(8,-8)]:
    after=sum(hit(v=='mech',t['x'],t['y'],lambda r:g)!=w for w,t,v in pairs)
    print('global shift',g,'slips ->',after)
R={0:(3,-5),1:(5,-6),2:(10,-5),3:(0,0)}
after=[(w,t['got'],hit(v=='mech',t['x'],t['y'],lambda r:R[r])) for w,t,v in pairs]
print('rounded per-row', sum(a!=w for w,g,a in after))
print('fixed:', collections.Counter((w,g) for w,g,a in after if g!=w and a==w).most_common(12))
print('newly broken:', [(w,a) for w,g,a in after if g==w and a!=w])
print('still wrong:', collections.Counter((w,a) for w,g,a in after if a!=w).most_common(10))

def centers(mech):
    rows,TOP,H=keymap(mech); C={}
    for ri,r in enumerate(rows):
        for c,x,w in r: C[c]=(x+w/2, TOP+ri*H+H/2, w)
    return C,H
def fit_offsets(fs, k_shrink=8):
    by=collections.defaultdict(list); rowb=collections.defaultdict(list)
    for w,t,v in fs:
        C,H=centers(v=='mech')
        if w in C: by[w].append((t['x']-C[w][0], t['y']-C[w][1])); rowb[rowof(w)].append(by[w][-1])
    rm={r:(st.mean(a for a,b in L), st.mean(b for a,b in L)) for r,L in rowb.items()}
    O={}
    for c,L in by.items():
        n=len(L); r=rm[rowof(c)]; a=n/(n+k_shrink)
        O[c]=(a*st.mean(x for x,y in L)+(1-a)*r[0], a*st.mean(y for x,y in L)+(1-a)*r[1])
    return O
def hit2(mech,x,y,O):
    C,H=centers(mech)
    letters=[c for c in C if len(c)==1 and c not in '⌫⇧⏎']+[' ']
    if y > 10+3*H: return hit(mech,x,y)   # bottom row unchanged
    best=min(letters, key=lambda c: ((x-C[c][0]-O.get(c,(0,0))[0])/C[c][2])**2 + ((y-C[c][1]-O.get(c,(0,0))[1])/H)**2)
    # keep shift/backspace where they are
    plain=hit(mech,x,y)
    return plain if plain in ('⇧','⌫') else best
tot_b=tot_a=0
for k in range(5):
    test=[p for i,p in enumerate(pairs) if i%5==k]; fit=[p for i,p in enumerate(pairs) if i%5!=k]
    O=fit_offsets(fit)
    tot_b+=sum(hit(v=='mech',t['x'],t['y'])!=w for w,t,v in test)
    tot_a+=sum(hit2(v=='mech',t['x'],t['y'],O)!=w for w,t,v in test)
print(f'per-key learned offsets, 5-fold held out: slips {tot_b} -> {tot_a} of {len(pairs)}')
O0={}
print('nearest-centre only (no learning):', sum(hit2(v=='mech',t['x'],t['y'],O0)!=w for w,t,v in pairs))
O=fit_offsets(pairs); print({c:(round(a,1),round(b,1)) for c,(a,b) in sorted(O.items())})
