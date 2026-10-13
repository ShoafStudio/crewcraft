/** Rooms added to the original Warm Studio, with separate work and rest positions. */
export interface Fill { from:number[]; to:number[]; block:string; binding:string|null }
export interface Seat { id:string; x:number; y:number; z:number; yaw:number }
export interface WorldPlan { title:string; fills:Fill[]; seats:Seat[] }
export function sharedOffice(agents:Array<{id:string;role:string;name?:string}>):WorldPlan {
  const leads=agents.filter(a=>a.role==='lead'&&a.id!=='builder');
  if(leads.length>4)throw new Error('The attached-house preset supports four managers. Ask Builder for a custom layout for larger rosters.');
  const owners=[...leads].sort((a,b)=>b.id.length-a.id.length);
  const teams=leads.map(lead=>({lead,members:agents.filter(a=>a.id===lead.id||a.role==='worker'&&owners.find(o=>a.id.startsWith(`team-${o.id}-`))?.id===lead.id)})).sort((a,b)=>b.members.length-a.members.length);
  teams.forEach((team,i)=>{if(team.members.length>(i===0?28:9))throw new Error('This team exceeds the attached-room preset capacity; request a custom Builder plan.');});
  const roster=(slot:number)=>teams[slot]?.members??[];
  const roomName=(slot:number,fallback:string)=>teams[slot]?.lead.name??teams[slot]?.lead.id??fallback;
  const fills:Fill[]=[],seats:Seat[]=[];
  const box=(a:number[],b:number[],block:string,binding:string|null=null)=>fills.push({from:a,to:b,block,binding});
  const cell=(x:number,y:number,z:number,block:string,binding:string|null=null)=>box([x,y,z],[x,y,z],block,binding);
  const timber='minecraft:stripped_dark_oak_log',plaster='agentcraft:plaster_panel';
  const stair=(f:string)=>`minecraft:dark_oak_stairs[facing=${f}]`;
  // Retire our detached office. The original studio itself is not cleared.
  box([-52,66,59],[48,79,113],'minecraft:air');
  box([-52,65,64],[48,65,113],'minecraft:grass_block');
  const room=(x0:number,x1:number,z0:number,z1:number,accent:string)=>{
    box([x0,66,z0],[x1,87,z1],'minecraft:air');
    box([x0,65,z0],[x1,65,z1],'minecraft:mud_bricks');
    box([x0+1,65,z0+1],[x1-1,65,z1-1],'minecraft:birch_planks');
    for(const z of [z0,z1]){
      box([x0,66,z],[x1,71,z],plaster);
      box([x0,66,z],[x1,66,z],'agentcraft:walnut_panel');
      for(let x=x0+3;x<x1-2;x+=5){
        box([x-1,67,z],[x+1,70,z],'minecraft:glass');
        box([x-1,66,z],[x+1,66,z],'minecraft:dark_oak_slab[type=top]');
      }
      for(let x=x0;x<=x1;x+=5)box([x,66,z],[x,72,z],timber);
      box([x0,72,z],[x1,72,z],`${timber}[axis=x]`);
    }
    for(const x of [x0,x1]){
      box([x,66,z0],[x,71,z1],plaster);
      box([x,66,z0],[x,66,z1],'agentcraft:walnut_panel');
      for(let z=z0+3;z<z1-2;z+=5)box([x,67,z-1],[x,70,z+1],'minecraft:glass');
      for(let z=z0;z<=z1;z+=5)box([x,66,z],[x,72,z],timber);
      box([x,72,z0],[x,72,z1],`${timber}[axis=z]`);
    }
    // Rugs, exposed beams and pools of lamplight reproduce the house's scale and materials.
    box([x0+2,66,z0+2],[x1-2,66,z1-2],`minecraft:${accent}_carpet`);
    for(let z=z0+4;z<z1;z+=8){
      box([x0+1,72,z],[x1-1,72,z],`${timber}[axis=x]`);
      for(const x of [x0+3,x1-3])cell(x,71,z,'minecraft:lantern[hanging=true]');
    }
  };
  const roof=(x0:number,x1:number,z0:number,z1:number)=>{
    const middle=Math.floor((z0+z1)/2);
    for(let z=z0-1;z<=(z1===-11?z1:z1+1);z++){
      const y=72+Math.min(z-z0+1,z1+1-z);
      const face=z<=middle?'south':'north';
      box([x0-1,y,z],[x1+1,y,z],z===middle?'minecraft:dark_oak_planks':`minecraft:waxed_cut_copper_stairs[facing=${face}]`);
      box([x0,y-1,z],[x1,y-1,z],'minecraft:pale_oak_planks');
      if(y>73)for(const x of [x0,x1])box([x,73,z],[x,y-1,z],plaster);
      for(const x of [x0-1,x1+1])cell(x,y,z,z===middle?'minecraft:dark_oak_planks':stair(face));
    }
    box([x0-1,73+Math.min(middle-z0+1,z1+1-middle),middle],[x1+1,73+Math.min(middle-z0+1,z1+1-middle),middle],'minecraft:dark_oak_slab');
  };
  const door=(x0:number,x1:number,z0:number,z1:number)=>box([x0,66,z0],[x1,69,z1],'minecraft:air');
  const label=(name:string,x:number,z:number)=>cell(x,70,z,'minecraft:dark_oak_wall_sign[facing=south]',`label:${name}`);
  const desk=(id:string,x:number,z:number)=>{
    box([x-1,66,z],[x+1,66,z],'minecraft:dark_oak_slab[type=top]');
    box([x-1,67,z],[x+1,68,z],'agentcraft:monitor[facing=south]',id);
    cell(x-1,66,z+1,stair('south'));
    seats.push({id,x:x-0.5,y:66,z:z+1.5,yaw:180});
  };
  const relax=(id:string,x:number,z:number,yaw=0)=>{
    const back=yaw===0?'north':yaw===180?'south':yaw===90?'east':'west';
    cell(x,66,z,`minecraft:brown_wool_stairs[facing=${back}]`);
    seats.push({id:`lounge-${id}`,x:x+0.5,y:66,z:z+0.5,yaw});
  };
  const table=(x:number,z:number)=>{cell(x,66,z,'minecraft:dark_oak_slab');cell(x,67,z,'minecraft:lantern');};
  const fireplace=(x:number,z:number)=>{
    box([x-2,66,z],[x+2,73,z],'minecraft:mud_bricks');
    box([x-1,67,z],[x+1,68,z],'minecraft:air');
    box([x-1,66,z],[x+1,66,z],'minecraft:glowstone');
    box([x-2,69,z-1],[x+2,69,z-1],'minecraft:dark_oak_slab[type=top]');
    box([x-1,74,z],[x+1,83,z],'minecraft:mud_bricks');
  };
  // Forge's larger team has a workshop plus its own common room in the west wing.
  room(-54,-25,-16,32,'orange');roof(-54,-25,-16,0);roof(-54,-25,0,16);roof(-54,-25,16,32);
  box([-53,66,12],[-26,71,12],plaster);door(-43,-40,12,12);
  const forge=roster(0);
  forge.forEach((a,i)=>{
    desk(a.id,-51+(i%7)*4,-8+Math.floor(i/7)*5);
    const group=Math.floor(i/4),slot=i%4,cx=-50+(group%4)*7,cz=18+Math.floor(group/4)*8;
    const spots=[[-2,-1,0],[1,-1,0],[-2,2,180],[1,2,180]][slot]!;
    relax(a.id,cx+spots[0]!,cz+spots[1]!,spots[2]);
  });
  for(let i=0;i<7;i++)table(-50+(i%4)*7,18+Math.floor(i/4)*8);
  fireplace(-39,31);label(roomName(0,'Team')+' | Workshop',-42,12);
  // Coordinator and Researcher share an east wing but have separate, furnished rooms.
  room(25,48,-11,11,'cyan');roof(25,48,-11,11);
  room(25,48,11,32,'green');roof(25,48,11,32);
  const coordinator=roster(1);
  coordinator.forEach((a,i)=>{desk(a.id,29+(i%3)*7,-8+Math.floor(i/3)*4);relax(a.id,28+(i%5)*4,6+Math.floor(i/5)*3,180);});
  table(37,4);box([46,66,3],[46,69,8],'minecraft:bookshelf');label(roomName(1,'Team')+' | Study',36,11);
  const researcher=roster(2);
  researcher.forEach((a,i)=>{desk(a.id,29+(i%3)*7,15+Math.floor(i/3)*4);relax(a.id,30+(i%3)*5,25+Math.floor(i/3)*3,0);});
  fireplace(37,31);table(36,27);label(roomName(2,'Team')+' | Library',36,32);
  // Two rear rooms extend the original central hall: Codex's studio and Builder's workshop.
  room(-23,-3,-33,-11,'purple');roof(-23,-3,-33,-11);
  room(3,23,-33,-11,'brown');roof(3,23,-33,-11);
  const codex=roster(3);
  codex.forEach((a,i)=>{desk(a.id,-19+(i%3)*6,-29+Math.floor(i/3)*5);relax(a.id,-20+(i%4)*4,-15,180);});
  table(-13,-18);box([-22,66,-20],[-22,69,-15],'minecraft:bookshelf');label(roomName(3,'Team')+' | Studio',-13,-11);
  for(let i=0;i<3;i++)cell(6+i*5,66,-13,'minecraft:brown_wool_stairs[facing=south]');
  desk('builder',13,-29);relax('builder',10,-16,0);table(13,-15);box([21,66,-25],[21,69,-16],'minecraft:bookshelf');label('BUILDER | Workshop',13,-11);
  // Covered corridor and short doorways physically join the original house; no detached campus.
  box([-2,66,-34],[2,74,-11],'minecraft:air');
  box([-2,65,-34],[2,65,-11],'agentcraft:terracotta_tile');
  box([-2,73,-34],[2,73,-11],'minecraft:waxed_cut_copper');
  for(const z of [-29,-21,-13])cell(0,72,z,'minecraft:lantern[hanging=true]');
  door(-4,4,-23,-21); // corridor doors into the two rear rooms
  door(-1,1,-11,-10); // original hall's central north window becomes its rear passage
  door(-25,-23,-15,-13); // rear connection keeps the original library shelves intact
  box([-25,65,-15],[-23,65,-13],'agentcraft:terracotta_tile');
  box([-25,70,-15],[-23,70,-13],timber);
  door(24,25,2,3);door(35,37,11,11);
  // Room doors and hall openings get matching dark timber lintels.
  box([24,70,2],[25,70,3],timber);
  box([-1,70,-10],[1,70,-10],`${timber}[axis=x]`);
  return {title:'Warm Studio house — connected team rooms, lounges and computer desks',fills,seats};
}
