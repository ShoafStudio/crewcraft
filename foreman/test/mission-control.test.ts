import {describe,it,expect} from 'vitest';
import {boardTasks} from '../src/agents/observer/mission-control.js';
const task={_id:'abc',title:'Decision',description:'Evidence',status:'inbox',priority:'P0',assignee:'User',createdAt:1,updatedAt:2};
describe('Mission Control projection',()=>{
 it('maps stable identities, human attention, priorities and source statuses',()=>{
  const rows=boardTasks([task,{...task,_id:'2',assignee:'Coordinator',priority:'P2',status:'in_progress'},{...task,_id:'3',assignee:'Forge',status:'review'},{...task,_id:'4',status:'done'},{...task,_id:'5',status:'archived'}],[{id:'coordinator',name:'Coordinator'},{id:'forge',name:'Forge'}],['User','Reviewer']);
  expect(rows).toHaveLength(4);expect(rows[0]).toMatchObject({assignee:'user',priority:3,status:'todo',summary:'Needs attention'});
  expect(rows[1]).toMatchObject({assignee:'coordinator',priority:1,status:'doing'});expect(rows[1]?.summary).toBeUndefined();
  expect(rows[2]).toMatchObject({assignee:'forge',status:'review',summary:'Needs attention'});expect(rows[3]?.summary).toBeUndefined();
 });
 it('rejects malformed feeds instead of claiming an empty board',()=>{expect(()=>boardTasks({tasks:[]},[],[])).toThrow();expect(()=>boardTasks([{...task,status:'unknown'}],[],[])).toThrow();});
});
