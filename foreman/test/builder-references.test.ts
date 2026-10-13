import {describe,it,expect} from 'vitest';
import {publicAddress,referenceUrls} from '../src/agents/observer/builder-references.js';
describe('Builder public reference reader',()=>{
 it('extracts bounded unique public links from conversation text',()=>{
  expect(referenceUrls('Use https://example.com/design. Then (https://example.com/design) and https://example.com/second')).toEqual(['https://example.com/design','https://example.com/second']);
  expect(referenceUrls('https://user:secret@example.com/ http://example.com:8080/ file:///etc/passwd')).toEqual([]);
  expect(referenceUrls(Array.from({length:10},(_,i)=>`https://example.com/${i}`).join(' '))).toHaveLength(3);
 });
 it('rejects private, loopback, link-local and Tailscale addresses',()=>{
  for(const ip of ['127.0.0.1','10.1.2.3','192.168.1.1','172.16.0.1','169.254.169.254','100.64.0.10','::1','fd00::1','fe80::1','::ffff:127.0.0.1','invalid'])expect(publicAddress(ip),ip).toBe(false);
  expect(publicAddress('1.1.1.1')).toBe(true);
  expect(publicAddress('2606:4700:4700::1111')).toBe(true);
 });
});
