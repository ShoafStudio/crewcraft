package dev.agentcraft.hq;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.block.ModBlocks;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.world.HqWorld;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/** Bounded office construction with durable before/after journals and conflict-aware undo. */
public final class OfficeConstruction {
    private static final int Z_OFFSET=30;
    private static final Map<String,Integer> SITES=Map.of("workshop",-52,"study",-26,"library",0,"studio",26);
    private static final Map<String,String> NAMES=Map.of("workshop","Workshop","study","Study","library","Library","studio","Studio");
    private static final Map<String,Job> jobs=new LinkedHashMap<>();
    private static volatile Map<String,View> views=Map.of();
    private static int ticks;
    private record Op(BlockPos pos,BlockState before,BlockState after) {}
    private record View(String state,int cursor,int total,BlockPos target,String error) {}
    private static final class Job {
        String manager,state="building",error="";
        int cursor;
        List<Op> ops=new ArrayList<>();
        Job(String manager){this.manager=manager;}
    }
    private OfficeConstruction(){}
    public static void init(){
        ServerLifecycleEvents.SERVER_STARTED.register(s->{jobs.clear();if(HqWorld.isHq(s))load(s);updateViews();});
        ServerLifecycleEvents.SERVER_STOPPING.register(s->{if(HqWorld.isHq(s))save(s);});
        ServerLifecycleEvents.SERVER_STOPPED.register(s->{jobs.clear();views=Map.of();});
        ServerTickEvents.END_SERVER_TICK.register(s->{if(!HqWorld.isHq(s)||++ticks%2!=0)return;tick(s);});
    }
    public static Map<BlockPos,BlockState> ownedBlocks(){
        Map<BlockPos,BlockState> result=new HashMap<>();
        for(Job j:jobs.values())if(!j.state.equals("undone"))for(Op op:j.ops)result.put(op.pos(),op.after());
        return result;
    }
    public static JsonObject command(MinecraftServer server,String action,String manager){
        if(!HqWorld.isHq(server))throw new IllegalStateException("Construction is available only in AgentCraft HQ");
        if(manager.equals("library"))manager="library";
        if(!manager.equals("all")&&!SITES.containsKey(manager))throw new IllegalArgumentException("Choose workshop, study, library, studio, or all");
        if(action.equals("status"))return status();
        if(BuilderConstruction.active()){
            if(action.equals("visit")){
                Anchor a=BuilderConstruction.anchorFor(manager);
                double x=a==null?0:a.x(),y=a==null?66:a.y(),z=a==null?100:a.z()+2;
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),"tp @p "+x+" "+y+" "+z+" 180 0");
                return BuilderConstruction.status();
            }
            throw new IllegalStateException("Use @builder to control the shared office; legacy office controls are retired.");
        }
        if(action.equals("visit")){
            double x=manager.equals("all")?0:SITES.get(manager)+11.5;
            int y=manager.equals("all")?65:66,z=manager.equals("all")?58:64;
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),"tp @p "+x+" "+y+" "+z+" 0 0");
            JsonObject result=status();result.addProperty("plan","At "+(manager.equals("all")?"the office campus":NAMES.get(manager)+"'s office entrance"));return result;
        }
        if(!Set.of("preview","start","pause","resume","cancel","undo").contains(action))throw new IllegalArgumentException("Unknown construction action");
        List<String> selected=manager.equals("all")?List.of("workshop","study","library","studio"):List.of(manager);
        if(action.equals("preview")){JsonObject r=status();r.addProperty("plan","Four separate offices south of the existing studio. Each has a manager's private office and 24 team desks. /build office <manager|all> starts block-by-block construction.");return r;}
        if(action.equals("start")){
            Map<String,Job> prepared=new LinkedHashMap<>();
            for(String id:selected){Job old=jobs.get(id);if(old!=null&&!old.state.equals("undone"))continue;prepared.put(id,prepare(server.overworld(),id));}
            jobs.putAll(prepared);save(server);publishBounds(server); // journal is durable before any block changes
        }else for(String id:selected){
            Job j=jobs.get(id);if(j==null)continue;
            switch(action){
                case "pause" -> {if(j.state.equals("building"))j.state="paused";}
                case "resume" -> {if(j.state.equals("undo-paused"))j.state="undoing";else if(Set.of("paused","cancelled","blocked").contains(j.state)){j.state="building";j.error="";}}
                case "cancel" -> {if(!Set.of("complete","undone").contains(j.state))j.state="cancelled";}
                case "undo" -> {if(!j.state.equals("undone")){j.state="undoing";j.cursor=j.ops.size();}}
                default -> {}
            }
        }
        save(server);updateViews();return status();
    }
    public static JsonObject status(){
        JsonArray array=new JsonArray();
        for(var e:views.entrySet()){JsonObject j=new JsonObject();View v=e.getValue();j.addProperty("manager",e.getKey());j.addProperty("name",NAMES.get(e.getKey()));j.addProperty("state",v.state());j.addProperty("error",v.error());j.addProperty("placed",v.cursor());j.addProperty("total",v.total());j.addProperty("percent",v.total()==0?100:Math.round(v.cursor()*100.0/v.total()));array.add(j);}
        JsonObject r=new JsonObject();r.add("jobs",array);return r;
    }
    private static void tick(MinecraftServer server){
        boolean changed=false;
        for(Job j:jobs.values()){
            if(!j.state.equals("building")&&!j.state.equals("undoing"))continue;
            boolean undo=j.state.equals("undoing");
            for(int k=0;k<6;k++){
                if((!undo&&j.cursor>=j.ops.size())||(undo&&j.cursor<=0)){j.state=undo?"undone":"complete";changed=true;break;}
                int i=undo?j.cursor-1:j.cursor;Op op=j.ops.get(i);BlockState current=server.overworld().getBlockState(op.pos());
                BlockState expected=undo?op.after():op.before(),desired=undo?op.before():op.after();
                if(current.equals(expected)&&server.overworld().getBlockEntity(op.pos())==null){server.overworld().setBlock(op.pos(),desired,Plan.FLAGS);}
                else if(!current.equals(desired)&&!undo){j.state="blocked";changed=true;j.error="A block changed at "+op.pos()+"; remove the conflict and resume.";break;}
                // Undo preserves player edits, including block entities, instead of overwriting them.
                j.cursor+=undo?-1:1;changed=true;
            }
        }
        if(changed){updateViews();if(ticks%40==0||jobs.values().stream().noneMatch(j->j.state.equals("building")||j.state.equals("undoing")))save(server);}
    }
    private static void updateViews(){Map<String,View> next=new LinkedHashMap<>();for(Job j:jobs.values()){BlockPos p=j.ops.isEmpty()?new BlockPos(SITES.get(j.manager)+11,66,34):j.ops.get(Math.max(0,Math.min(j.ops.size()-1,j.cursor))).pos();next.put(j.manager,new View(j.state,j.cursor,j.ops.size(),p,j.error));}views=Map.copyOf(next);}
    public static boolean crewBuilding(String agentId){
        if(!agentId.startsWith("team-"))return false;int end=agentId.lastIndexOf('-');if(end<5)return false;
        View v=views.get(agentId.substring(5,end));return v!=null&&(v.state().equals("building")||v.state().equals("undoing"));
    }
    public static String crewActivity(String agentId){
        int end=agentId.lastIndexOf('-');String manager=agentId.substring(5,end);View v=views.get(manager);
        if(crewBuilding(agentId))return "Building "+NAMES.get(manager)+"'s office · "+Math.round(100.0*v.cursor()/Math.max(1,v.total()))+"%";
        return "Available · no construction assignment";
    }
    /** Client-side placement targets. Workers walk between their office and the active site. */
    public static @Nullable Anchor anchorFor(String agentId){
        String manager=agentId;int worker=-1;
        if(agentId.startsWith("team-")){
            int dash=agentId.lastIndexOf('-');if(dash<5)return null;
            manager=agentId.substring(5,dash);try{worker=Integer.parseInt(agentId.substring(dash+1));}catch(NumberFormatException e){return null;}
        }
        Integer x=SITES.get(manager);if(x==null)return null;View v=views.get(manager);if(v==null||v.state().equals("undone")){if(worker<0)return null;return new Anchor("site_"+agentId,x+2.5+(worker%6)*3,65,30.5+Z_OFFSET+(worker/6)%4,0,0);}
        if(worker<0){if(!v.state().equals("complete"))return null;return new Anchor("desk_"+agentId,x+11.5,66,60.5+Z_OFFSET,180,0);}
        if(worker>=24)return new Anchor("site_"+agentId,x+2.5+(worker%6)*3,65,30.5+Z_OFFSET+(worker/6)%4,0,0);
        int slot=Math.floorMod(worker,24);return new Anchor("desk_"+agentId,x+3.5+(slot%4)*5,66,37.5+Z_OFFSET+(slot/4)*3,180,0);
    }
    private static Job prepare(ServerLevel level,String manager){
        int x=SITES.get(manager);Map<BlockPos,BlockState> plan=new LinkedHashMap<>();
        for(int z=29;z<=33;z++)for(int dx=0;dx<=22;dx++){put(plan,x+dx,64,z,Blocks.STONE_BRICKS);for(int y=65;y<=70;y++)put(plan,x+dx,y,z,Blocks.AIR);}
        // Clear only the new office footprint, leaving the original studio and all other world cells intact.
        for(int y=66;y<=79;y++)for(int z=34;z<=63;z++)for(int dx=0;dx<=22;dx++)put(plan,x+dx,y,z,Blocks.AIR);
        for(int z=34;z<=63;z++)for(int dx=0;dx<=22;dx++)put(plan,x+dx,65,z,ModBlocks.OAK_PARQUET);
        for(int y=66;y<=72;y++)for(int z=34;z<=63;z++)for(int dx=0;dx<=22;dx++){
            if(dx!=0&&dx!=22&&z!=34&&z!=63)continue;
            boolean window=y>=67&&y<=70&&dx!=0&&dx!=22 || y>=67&&y<=70&&z>35&&z<62;
            put(plan,x+dx,y,z,window?Blocks.GLASS:ModBlocks.PLASTER_PANEL);
        }
        for(int z=34;z<=63;z++)for(int dx=0;dx<=22;dx++)put(plan,x+dx,73,z,dx%5==1?Blocks.GLASS:ModBlocks.WALNUT_PANEL);
        for(int dx=0;dx<=22;dx++)for(int y=66;y<=71;y++)put(plan,x+dx,y,55,y>=67?Blocks.GLASS:ModBlocks.WALNUT_PANEL);
        for(int dz:new int[]{34,55})for(int dx=10;dx<=12;dx++)for(int y=66;y<=68;y++)put(plan,x+dx,y,dz,Blocks.AIR);
        for(int slot=0;slot<24;slot++){
            int cx=x+3+(slot%4)*5,cz=37+(slot/4)*3;
            put(plan,cx,66,cz,Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.SOUTH));
            put(plan,cx,66,cz-1,ModBlocks.WALNUT_PANEL);put(plan,cx+1,66,cz-1,ModBlocks.WALNUT_PANEL);
        }
        put(plan,x+11,66,60,Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.SOUTH));
        for(int dx=9;dx<=13;dx++)put(plan,x+dx,66,59,ModBlocks.WALNUT_PANEL);
        for(int z:new int[]{36,42,48,57,62})for(int dx:new int[]{1,21})put(plan,x+dx,71,z,ModBlocks.GLOW_PANEL);
        for(int z=57;z<=61;z++)put(plan,x+1,66,z,Blocks.BOOKSHELF);
        put(plan,x+20,66,60,Blocks.FLOWER_POT);
        Block carpet=switch(manager){case "workshop"->Blocks.CARPET.pick(net.minecraft.world.item.DyeColor.ORANGE);case "study"->Blocks.CARPET.pick(net.minecraft.world.item.DyeColor.CYAN);case "library"->Blocks.CARPET.pick(net.minecraft.world.item.DyeColor.GREEN);default->Blocks.CARPET.pick(net.minecraft.world.item.DyeColor.PURPLE);};
        for(int z=56;z<=58;z++)for(int dx=9;dx<=13;dx++)put(plan,x+dx,66,z,carpet);
        for(int z=34;z<=63;z+=5)for(int dx:new int[]{0,22})for(int y=66;y<=72;y++)put(plan,x+dx,y,z,ModBlocks.WALNUT_TRIM);
        for(int dx=10;dx<=12;dx++)put(plan,x+dx,65,33,Blocks.SPRUCE_SLAB);
        // A level access path joins the campus gate; no clearing outside the office box.
        for(int z=26;z<=33;z++)for(int dx=10;dx<=12;dx++)put(plan,x+dx,64,z,Blocks.STONE_BRICKS);
        int center=x+11;for(int cx=Math.min(center,0);cx<=Math.max(center,0);cx++)for(int z=25;z<=27;z++)put(plan,cx,64,z,Blocks.STONE_BRICKS);
        Job job=new Job(manager);
        for(var e:plan.entrySet()){
            BlockState before=level.getBlockState(e.getKey()),after=e.getValue();if(before.equals(after))continue;
            if(level.getBlockEntity(e.getKey())!=null||!natural(before))throw new IllegalStateException("Office site occupied at "+e.getKey()+". Existing construction is protected; found "+BuiltInRegistries.BLOCK.getKey(before.getBlock())+".");
            job.ops.add(new Op(e.getKey(),before,after));
        }
        // Ground first, clearance next, then the actual building rises from floor to roof.
        job.ops.sort(Comparator.comparingInt((Op o)->o.pos().getY()<=65?0:o.after().isAir()?1:2).thenComparingInt(o->o.pos().getY()).thenComparingInt(o->o.pos().getZ()));
        if(job.ops.isEmpty())job.state="complete";return job;
    }
    private static boolean natural(BlockState state){String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();return state.isAir()||Set.of("grass_block","dirt","coarse_dirt","rooted_dirt","stone","gravel","sand","short_grass","tall_grass","fern","large_fern","dandelion","poppy","snow","moss_block","vine","dead_bush").contains(id)||id.endsWith("_leaves")||id.endsWith("_log");}
    private static void put(Map<BlockPos,BlockState> p,int x,int y,int z,Block block){put(p,x,y,z,block.defaultBlockState());}
    private static void put(Map<BlockPos,BlockState> p,int x,int y,int z,BlockState state){p.put(new BlockPos(x,y,z+Z_OFFSET),state);}
    private static void publishBounds(MinecraftServer server){var old=Anchors.current();var b=old.bounds();Anchors.publish(server,new Anchors.Layout(old.name(),old.revision(),new Anchors.Bounds(Math.min(-55,b==null?-55:b.minX()),64,Math.min(-10,b==null?-10:b.minZ()),Math.max(52,b==null?52:b.maxX()),Math.max(81,b==null?81:b.maxY()),Math.max(95,b==null?95:b.maxZ())),old.anchors()));}
    private static Path file(MinecraftServer s){return s.getWorldPath(LevelResource.ROOT).resolve("headquarters-construction.nbt");}
    private static void save(MinecraftServer server){
        CompoundTag root=new CompoundTag();ListTag list=new ListTag();
        for(Job j:jobs.values()){
            CompoundTag t=new CompoundTag();t.putString("manager",j.manager);t.putString("state",j.state);t.putInt("cursor",j.cursor);t.putString("error",j.error);ListTag ops=new ListTag();
            for(Op o:j.ops){CompoundTag op=new CompoundTag();op.putLong("pos",o.pos().asLong());op.put("before",NbtUtils.writeBlockState(o.before()));op.put("after",NbtUtils.writeBlockState(o.after()));ops.add(op);}t.put("ops",ops);list.add(t);
        }root.put("jobs",list);
        try{Path f=file(server),tmp=f.resolveSibling("headquarters-construction.tmp");NbtIo.writeCompressed(root,tmp);Files.move(tmp,f,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(Exception e){for(Job j:jobs.values())if(j.state.equals("building"))j.state="paused";updateViews();throw new IllegalStateException("Could not save construction journal; construction paused",e);}
    }
    private static void load(MinecraftServer server){
        if(!Files.exists(file(server)))return;
        try{ListTag list=NbtIo.readCompressed(file(server),NbtAccounter.unlimitedHeap()).getListOrEmpty("jobs");for(int i=0;i<list.size();i++){CompoundTag t=list.getCompoundOrEmpty(i);String id=t.getStringOr("manager","");if(!SITES.containsKey(id))continue;Job j=new Job(id);j.state=t.getStringOr("state","paused");j.cursor=t.getIntOr("cursor",0);j.error=t.getStringOr("error","");ListTag ops=t.getListOrEmpty("ops");for(int k=0;k<ops.size();k++){CompoundTag op=ops.getCompoundOrEmpty(k);j.ops.add(new Op(BlockPos.of(op.getLongOr("pos",0)),NbtUtils.readBlockState(BuiltInRegistries.BLOCK,op.getCompoundOrEmpty("before")),NbtUtils.readBlockState(BuiltInRegistries.BLOCK,op.getCompoundOrEmpty("after"))));}if(j.state.equals("building"))j.state="paused";else if(j.state.equals("undoing"))j.state="undo-paused";jobs.put(id,j);}}catch(Exception e){AgentCraft.LOGGER.error("Construction journal could not be read; leaving world unchanged",e);}
    }
}
