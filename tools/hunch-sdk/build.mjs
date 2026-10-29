import {build} from 'esbuild';
await build({entryPoints:[new URL('bridge.mjs',import.meta.url).pathname],bundle:true,minify:true,legalComments:'linked',target:'chrome110',outfile:new URL('../../hunch/src/main/assets/paper/exchange-sdk.js',import.meta.url).pathname});
