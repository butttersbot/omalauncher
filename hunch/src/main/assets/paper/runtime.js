'use strict';
// Adapter for the exported Design Canvas vocabulary. Templates are bundled author code;
// all remote/provider values enter through text nodes, never HTML or executable expressions.
window.Paper = (()=>{
 const cache=new Map();let active=null,queued=false;
 class DCLogic{constructor(props){this.props=props;this.state={}}setState(next){Object.assign(this.state,next);schedule()} }
 function lookup(scope,path){path=path.trim();if(path==='true')return true;if(path==='false')return false;if(path==='null')return null;if(/^-?\d+(\.\d+)?$/.test(path))return Number(path);return path.split('.').reduce((v,k)=>v==null?undefined:v[k],scope)}
 function value(s,scope){const m=/^\s*{{\s*([^}]+)\s*}}\s*$/.exec(s||'');return m?lookup(scope,m[1]):(s||'').replace(/{{\s*([^}]+)\s*}}/g,(_,k)=>{const v=lookup(scope,k);return v==null?'':String(v)})}
 function fragment(nodes,scope,key){const out=document.createDocumentFragment();Array.from(nodes).forEach((node,i)=>{
   const k=key+'.'+i;
   if(node.nodeType===3){out.append(document.createTextNode(value(node.textContent,scope)));return}
   if(node.nodeType!==1)return;
   const tag=node.localName;
   if(tag==='helmet'){for(const style of node.querySelectorAll('style')){if(!document.getElementById('template-'+active.name)){const s=style.cloneNode(true);s.id='template-'+active.name;document.head.append(s)}}return}
   if(tag==='sc-if'){if(value(node.getAttribute('value'),scope))out.append(fragment(node.childNodes,scope,k));return}
   if(tag==='sc-for'){const list=value(node.getAttribute('list'),scope);if(Array.isArray(list))list.forEach((v,n)=>out.append(fragment(node.childNodes,{...scope,[node.getAttribute('as')||'item']:v},k+'['+n+']')));return}
   if(tag==='dc-import'){const imported=cache.get(node.getAttribute('name'));if(imported)out.append(fragment(imported.template.childNodes,scope,k));return}
   if(tag==='script'||tag==='link')return;
   const el=node.cloneNode(false);el._paperKey=k;el._paperEvents={};
   for(const attr of Array.from(node.attributes)){
     const v=value(attr.value,scope);
     if(attr.name.startsWith('on')){el.removeAttribute(attr.name);if(typeof v==='function'){const event=attr.name.toLowerCase()==='onchange'?'input':attr.name.slice(2).toLowerCase();el._paperEvents[event]=v;el.addEventListener(event,v)}continue}
     if(attr.name.startsWith('hint-')){el.removeAttribute(attr.name);continue}
     el.setAttribute(attr.name,v==null?'':String(v));
     if(attr.name==='value'&&'value'in el)el.value=v==null?'':v;
   }
   el.append(fragment(node.childNodes,scope,k));out.append(el);
 });return out}
 function morph(old,node){
   if(!old||old.nodeType!==node.nodeType||old.nodeName!==node.nodeName||old._paperKey!==node._paperKey){old.replaceWith(node);return}
   if(node.nodeType===3){if(old.textContent!==node.textContent)old.textContent=node.textContent;return}
   if(node.nodeType!==1)return;
   for(const a of Array.from(old.attributes))if(!node.hasAttribute(a.name))old.removeAttribute(a.name);
   for(const a of Array.from(node.attributes))if(old.getAttribute(a.name)!==a.value)old.setAttribute(a.name,a.value);
   for(const [type,fn]of Object.entries(old._paperEvents||{}))old.removeEventListener(type,fn);
   old._paperEvents=node._paperEvents||{};for(const [type,fn]of Object.entries(old._paperEvents))old.addEventListener(type,fn);
   if('value'in node&&old.value!==node.value&&document.activeElement!==old)old.value=node.value;
   const a=Array.from(old.childNodes),b=Array.from(node.childNodes);
   for(let i=0;i<Math.max(a.length,b.length);i++){if(!a[i])old.append(b[i]);else if(!b[i])a[i].remove();else morph(a[i],b[i])}
 }
 async function load(name){if(cache.has(name))return cache.get(name);if(!/^[A-Za-z]+$/.test(name))throw Error('Unknown page');const text=await(await fetch('mockup/'+name+'.dc.html')).text();const doc=new DOMParser().parseFromString(text,'text/html');const script=doc.querySelector('script[data-dc-script]');const props=JSON.parse(script.dataset.props);const Component=new Function('DCLogic',script.textContent+';return Component;')(DCLogic);const item={name,template:doc.querySelector('x-dc'),Component,props};cache.set(name,item);for(const imp of item.template.querySelectorAll('dc-import'))await load(imp.getAttribute('name'));return item}
 async function show(name,decorate){const source=await load(name);active?.logic?.componentWillUnmount?.();active={...source,logic:new source.Component(source.props),decorate};if(decorate)decorate(active.logic);document.getElementById('stage').replaceChildren();render();return active.logic}
 function schedule(){if(!queued){queued=true;requestAnimationFrame(()=>{queued=false;render()})}}
 function render(){if(!active)return;try{if(active.build){const stage=document.getElementById('stage'),fresh=active.build();fresh.id='scene';if(stage.firstChild)morph(stage.firstChild,fresh);else stage.append(fresh);active.after?.(stage.firstChild);fit();return}let vals=active.logic.renderVals();if(active.values)vals=active.values(vals);const result=fragment(active.template.childNodes,vals,'root');const holder=document.createElement('div');holder.append(result);const fresh=holder.querySelector('div');if(!fresh)throw Error('Missing scene');fresh.id='scene';if(active.prepare)active.prepare(fresh,vals);const stage=document.getElementById('stage');if(stage.firstChild)morph(stage.firstChild,fresh);else stage.append(fresh);if(active.after)active.after(stage.firstChild,vals);fit();}catch(e){window.paperError?.(e)}}
 function fit(){window.requestListingQuotes?.();window.renderBetOverlay?.();const scene=document.getElementById('scene');if(!scene||!active)return;const viewport=document.getElementById('viewport');let w=active.props.$preview.width,h=active.props.$preview.height;
   if(active.dimensions){const dim=active.dimensions();w=dim.width;h=dim.height}
   const scale=Math.min(active.maxScale||Infinity,viewport.clientWidth/w,active.fitHeight?viewport.clientHeight/h:Infinity);scene.style.transform='scale('+scale+')';const stage=document.getElementById('stage');stage.style.width=w*scale+'px';stage.style.height=(active.autoHeight?scene.scrollHeight:h)*scale+'px';
 }
 document.fonts?.addEventListener('loadingdone',fit);
 window.addEventListener('resize',()=>{fit();window.paperResize?.()});
 function custom(name,build,dimensions,after){active?.logic?.componentWillUnmount?.();active={name,build,dimensions,after,props:{$preview:dimensions()}};document.getElementById('stage').replaceChildren();render()}
 return {load,show,custom,render,schedule,fit,get active(){return active},value,morph};
})();
