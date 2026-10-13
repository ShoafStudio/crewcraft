import {describe,it,expect} from 'vitest';
import {parseBuild} from '../src/agents/observer/construction.js';
const resolve=(name:string)=>({library:'library',study:'study',workshop:'workshop',studio:'studio'}[name]);
describe('Minecraft build commands',()=>{
  it('routes a manager instruction to only that office',()=>{
    expect(parseBuild('/build office','study',resolve)).toEqual({action:'start',manager:'study'});
    expect(parseBuild('/build office @library','all',resolve)).toEqual({action:'start',manager:'library'});
    expect(parseBuild('/build pause all','all',resolve)).toEqual({action:'pause',manager:'all'});
  });
  it('never turns ordinary agent messages or arbitrary commands into world mutations',()=>{
    expect(parseBuild('please build a project','study',resolve)).toBeNull();
    expect(()=>parseBuild('/build exec rm','study',resolve)).toThrow('Use /build');
    expect(()=>parseBuild('/build office unknown','all',resolve)).toThrow('Choose');
    expect(()=>parseBuild('/build office study extra','all',resolve)).toThrow('Use /build');
  });
});
