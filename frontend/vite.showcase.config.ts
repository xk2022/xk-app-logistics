import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { fileURLToPath } from 'node:url'
import { isAbsolute, relative } from 'node:path'
const frontend=fileURLToPath(new URL('.',import.meta.url))
function normalizeModule(id:string){const virtual=id.startsWith('\0');let value=id.replace(/^\0+/,'').replaceAll('\\','/');if(isAbsolute(value))value=relative(frontend,value).replaceAll('\\','/');if(value.includes('../')||isAbsolute(value))throw new Error('Showcase module is outside the isolated frontend tree.');return `${virtual?'virtual:':''}${value}`}
export default defineConfig({root:fileURLToPath(new URL('./showcase',import.meta.url)),base:'/logistics/',publicDir:false,plugins:[react(),{name:'showcase-module-provenance',generateBundle(_options,bundle){const modules=[...new Set(Object.values(bundle).filter(output=>output.type==='chunk').flatMap(output=>Object.keys(output.modules)).map(normalizeModule))].sort();this.emitFile({type:'asset',fileName:'showcase-build.json',source:JSON.stringify({entry:'showcase/main.tsx',base:'/logistics/',modules},null,2)+'\n'})}}],build:{outDir:fileURLToPath(new URL('./dist-showcase',import.meta.url)),emptyOutDir:true,target:'es2022',assetsInlineLimit:0,sourcemap:false,modulePreload:{polyfill:false},cssCodeSplit:false}})
