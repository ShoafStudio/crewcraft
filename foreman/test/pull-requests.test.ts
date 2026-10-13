import {describe,it,expect} from 'vitest';
import {pullRequestLink} from '../src/agents/observer/pull-requests.js';
describe('board PR links',()=>{
 it('extracts only canonical GitHub pull requests without shell interpolation',()=>{
  expect(pullRequestLink('Review [PR](https://github.com/YOUR_ACCOUNT/crewcraft/pull/12).')).toBe('https://github.com/YOUR_ACCOUNT/crewcraft/pull/12');
  for(const url of ['https://github.com.evil.test/a/b/pull/12','https://evil.test/a/b/pull/12','https://github.com/a/b/pull/12abc','$(touch /tmp/nope)','https://github.com/a/b/issues/12'])expect(pullRequestLink(url)).toBeUndefined();
 });
});
