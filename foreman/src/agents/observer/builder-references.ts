import fs from 'node:fs';
import path from 'node:path';
import {lookup} from 'node:dns/promises';
import {BlockList,isIP} from 'node:net';
import {createHash} from 'node:crypto';
import {chromium} from 'playwright-core';

const denied=new BlockList();
for(const [ip,bits] of [['0.0.0.0',8],['10.0.0.0',8],['100.64.0.0',10],['127.0.0.0',8],['169.254.0.0',16],['172.16.0.0',12],['192.168.0.0',16],['192.0.0.0',24],['192.0.2.0',24],['198.18.0.0',15],['198.51.100.0',24],['203.0.113.0',24],['224.0.0.0',3]] as const)denied.addSubnet(ip,bits,'ipv4');
for(const [ip,bits] of [['::',128],['::1',128],['fc00::',7],['fe80::',10],['ff00::',8]] as const)denied.addSubnet(ip,bits,'ipv6');
export function publicAddress(address:string):boolean {
  if(address.toLowerCase().startsWith('::ffff:'))return false;
  const family=isIP(address);return !!family&&!denied.check(address,family===4?'ipv4':'ipv6');
}
export function referenceUrls(text:string):string[]{
  return [...new Set((text.match(/https?:\/\/[^\s<>"`]+/g)??[]).map(s=>s.replace(/[.,;!)\]}]+$/,'')))].filter(s=>{
    try{const u=new URL(s);return !u.username&&!u.password&&(!u.port||u.port==='443'||u.port==='80');}catch{return false;}
  }).slice(0,3);
}
export type Reference={url:string;title?:string;text?:string;images:string[];error?:string;capturedAt:string};
/** A fresh, isolated normal Chrome window: read public pages only, no login, clicks or downloads. */
export async function readReferences(urls:string[],dir:string):Promise<Reference[]>{
  if(!urls.length)return [];
  fs.mkdirSync(dir,{recursive:true,mode:0o700});
  const results:Reference[]=[];
  const browser=await chromium.launch({channel:'chrome',headless:false,timeout:15000}).catch(()=>null);
  if(!browser)return urls.map(url=>({url,images:[],capturedAt:new Date().toISOString(),error:'Public reference browser unavailable; Google Chrome must be installed. Use web search for this URL.'}));
  try{
    const context=await browser.newContext({acceptDownloads:false,viewport:{width:1400,height:1100},serviceWorkers:'block'});
    await context.routeWebSocket('**/*',socket=>socket.close());
    const checks=new Map<string,Promise<boolean>>();
    const allowed=(url:string)=>{
      try{
        const u=new URL(url);
        if(!['https:','http:'].includes(u.protocol)||u.username||u.password||(u.port&&!['80','443'].includes(u.port)))return Promise.resolve(false);
        const host=u.hostname.replace(/^\[|\]$/g,'');
        if(!checks.has(host))checks.set(host,lookup(host,{all:true}).then(records=>records.length>0&&records.every(r=>publicAddress(r.address))).catch(()=>false));
        return checks.get(host)!;
      }catch{return Promise.resolve(false);}
    };
    await context.route('**/*',async route=>{
      const request=route.request();
      if(request.method()!=='GET'||['media','websocket'].includes(request.resourceType())||!await allowed(request.url()))await route.abort();
      else await route.continue();
    });
    for(const url of urls.slice(0,3)){
      const result:Reference={url,images:[],capturedAt:new Date().toISOString()};results.push(result);
      const page=await context.newPage();page.setDefaultTimeout(5000);
      try{
        if(!await allowed(url))throw new Error('Only public web references are supported');
        const response=await page.goto(url,{waitUntil:'domcontentloaded',timeout:20000});
        if(!response?.ok())throw new Error(`Page returned HTTP ${response?.status()??'unknown'}; access was not bypassed`);
        result.title=await page.title();
        result.text=(await page.locator('body').innerText()).slice(0,18000);
        if(/just a moment|verify you are human|access denied/i.test(result.title))throw new Error('Site requires human verification');
        const prefix=path.join(dir,createHash('sha256').update(url).digest('hex').slice(0,20));
        await page.screenshot({path:prefix+'.png',timeout:5000});result.images.push(prefix+'.png');
        // Give the model actual reference pixels, not just image URLs or invented visual details.
        const pictures=page.locator('img');
        for(let i=0;i<Math.min(await pictures.count(),40)&&result.images.length<2;i++){
          const picture=pictures.nth(i),box=await picture.boundingBox();
          if(!box||box.width<280||box.height<180)continue;
          const imagePath=prefix+`-${result.images.length}.png`;
          try{await picture.screenshot({path:imagePath,timeout:3000});result.images.push(imagePath);}catch{}
        }
      }catch(e){result.error=(e as Error).message.slice(0,300);}
      finally{await page.close();}
    }
    await context.close();
  }catch(e){
    for(const url of urls.slice(0,3))if(!results.some(r=>r.url===url))results.push({url,images:[],capturedAt:new Date().toISOString(),error:'Browser read failed: '+(e as Error).message.slice(0,200)});
  }finally{await browser.close().catch(()=>{});}
  return results;
}
