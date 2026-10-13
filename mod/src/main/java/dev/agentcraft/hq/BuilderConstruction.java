package dev.agentcraft.hq;

import com.google.gson.*;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.block.entity.StationBlockEntity;
import dev.agentcraft.block.PanelBlock;
import net.minecraft.core.Direction;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.world.HqWorld;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.*;
import java.util.*;
import org.jspecify.annotations.Nullable;

/** Validated, paced world edits. No model code or arbitrary server commands are executed. */
public final class BuilderConstruction {
    private record Cell(BlockState state,String binding) {}
    private record Edit(BlockPos pos,Cell before,Cell after,boolean ownedBefore) {}
    private static final Map<BlockPos,Cell> owned=new HashMap<>();
    private static final List<Edit> edits=new ArrayList<>();
    private static Map<String,Anchor> seats=new LinkedHashMap<>(),previousSeats=new LinkedHashMap<>();
    private static volatile Map<String,Anchor> visibleSeats=Map.of();
    private static volatile String state="idle",title="Builder ready",error="";
    private static volatile int cursor=0,total=0;
    private static int ticks=0;
    private static volatile boolean ready=false;
    private static volatile double builderX=5.5, builderZ=-35;
    private static final Set<String> VANILLA=Set.of("air","stone_bricks","smooth_stone","quartz_block","glass","glass_pane","oak_planks","dark_oak_planks","spruce_planks","oak_log","spruce_log","oak_leaves","bookshelf","glowstone","sea_lantern","grass_block","dirt","white_concrete","light_gray_concrete","gray_concrete","black_concrete","cyan_concrete","blue_concrete","orange_concrete","green_concrete","purple_concrete","dark_oak_slab","dark_oak_stairs","spruce_stairs","gray_carpet","cyan_carpet","green_carpet","purple_carpet","orange_carpet","white_carpet","blue_carpet","brown_carpet","brown_wool_stairs","birch_planks","pale_oak_planks","stripped_dark_oak_log","mud_bricks","dark_oak_wall_sign","lantern","waxed_cut_copper","waxed_cut_copper_stairs");
    private static final Set<String> CUSTOM=Set.of("oak_parquet","walnut_panel","walnut_trim","plaster_panel","glow_panel","monitor","terracotta_tile");
    public static void init(){
        ServerLifecycleEvents.SERVER_STARTED.register(s->{reset();if(HqWorld.isHq(s)){load(s);ready=true;}});
        ServerLifecycleEvents.SERVER_STOPPING.register(s->{if(HqWorld.isHq(s)&&ready)save(s);});
        ServerLifecycleEvents.SERVER_STOPPED.register(s->reset());
        ServerTickEvents.END_SERVER_TICK.register(s->{if(HqWorld.isHq(s)&&++ticks%2==0)tick(s);});
    }
    private static void reset(){ready=false;owned.clear();edits.clear();seats=new LinkedHashMap<>();previousSeats=new LinkedHashMap<>();visibleSeats=Map.of();state="idle";title="Builder ready";error="";cursor=total=0;}
    public static boolean active(){return !owned.isEmpty()||!state.equals("idle");}
    public static boolean building(){return state.equals("building")||state.equals("undoing");}
    public static String activity(){return building()?title+" · "+percent()+"%":state.equals("blocked")?error:"Available · talk to me to build";}
    public static @Nullable Anchor anchorFor(String id){
        if(id.equals("builder")&&!visibleSeats.containsKey(id))return new Anchor("site_builder",5.5,66,8.5,0,0);
        return visibleSeats.get(id);
    }
    public static @Nullable Anchor anchorFor(String id,String station){
        if(id.startsWith("construction-")){
            int slot=switch(id){case "construction-mason"->0;case "construction-carpenter"->1;case "construction-roofer"->2;default->-1;};
            if(slot<0)return null;
            if(building()){
                // A safe ground-level apron beside the active wing; never move real agents here.
                double x=builderX<-24?-50.5+slot*4:builderX>24?30.5+slot*4:6.5+slot*4;
                double z=builderZ>58?60.5:builderX<-24||builderX>24?34.5:-35.5;
                return new Anchor("site_"+id,x,65,z,180,0);
            }
            return new Anchor("lounge_"+id,6.5+slot*5,66,-12.5,180,0);
        }
        Anchor a=visibleSeats.get(station.equals("lounge")?"lounge-"+id:id);
        return a==null?null:new Anchor((station.equals("lounge")?"lounge_":"desk_")+id,a.x(),a.y(),a.z(),a.yaw(),a.pitch());
    }
    private static int percent(){return total==0?0:(int)Math.round(cursor*100.0/total);}
    public static JsonObject status(){JsonObject r=new JsonObject();r.addProperty("state",state);r.addProperty("title",title);r.addProperty("error",error);r.addProperty("placed",cursor);r.addProperty("total",total);r.addProperty("percent",percent());r.addProperty("desks",visibleSeats.keySet().stream().filter(id->!id.startsWith("lounge-")).count());r.addProperty("lounges",visibleSeats.keySet().stream().filter(id->id.startsWith("lounge-")).count());return r;}
    public static JsonObject command(MinecraftServer s,String action,@Nullable JsonObject plan){
        if(!HqWorld.isHq(s))throw new IllegalStateException("Open AgentCraft HQ first");
        if(!ready)throw new IllegalStateException("Headquarters is still loading; retry after the world is ready");
        switch(action){
            case "status" -> {return status();}
            case "survey" -> {return survey(s);}
            case "validate" -> {prepare(s,Objects.requireNonNull(plan,"Missing build plan"),true);JsonObject r=new JsonObject();r.addProperty("valid",true);return r;}
            case "submit" -> {if(building()||state.equals("paused")||state.equals("undo-paused")||state.equals("blocked"))throw new IllegalStateException("Finish or undo the current build before submitting another");prepare(s,Objects.requireNonNull(plan,"Missing build plan"),false);}
            case "pause" -> {if(state.equals("building"))state="paused";else if(state.equals("undoing"))state="undo-paused";save(s);}
            case "resume" -> {if(state.equals("paused")||state.equals("blocked")){state="building";error="";}else if(state.equals("undo-paused"))state="undoing";save(s);}
            case "undo" -> {if(!edits.isEmpty()&&!state.equals("undone")&&!state.equals("undoing")){state="undoing";error="";}save(s);}
            case "visit" -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(),"tp @p 0 66 0 180 0");
            default -> throw new IllegalArgumentException("Unknown builder action");
        }
        return status();
    }
    /** Fresh whole-site snapshot, independent of player position. Loads site chunks; never edits blocks. */
    private static JsonObject survey(MinecraftServer server){
        JsonObject result=status();
        result.add("originalHouse",StudioHqBuilder.planningContext());
        var layout=Anchors.current();result.addProperty("layoutName",layout.name());result.addProperty("layoutRevision",layout.revision());
        JsonArray stations=new JsonArray();
        for(var entry:layout.anchors().entrySet()){
            JsonObject a=new JsonObject();var anchor=entry.getValue();a.addProperty("name",entry.getKey());a.addProperty("x",anchor.x());a.addProperty("y",anchor.y());a.addProperty("z",anchor.z());a.addProperty("yaw",anchor.yaw());stations.add(a);
        }
        result.add("savedWorldAnchors",stations);
        result.addProperty("anchorPrecedence","Construction seats override original desk/lounge anchors for the same identity. Saved anchors describe intended station positions, not a guarantee that players have not moved blocks.");
        result.addProperty("constructionBounds","x=-56..52, y=64..192, z=-36..118; a limit, not permission to overwrite existing construction");
        result.addProperty("ownershipRules","Player edits and existing unowned block entities are protected. Original generated blocks are reusable only when unchanged and in supported extension areas (x<=-24 OR x>=24 OR z<=-11), or the explicit rear doorway x=-1..1,y=66..70,z=-10. Original interior furniture is not automatically Builder-owned. Air/natural terrain elsewhere still requires a site survey and preserving house circulation.");
        JsonArray anchors=new JsonArray();
        for(var entry:visibleSeats.entrySet()){
            JsonObject a=new JsonObject();a.addProperty("id",entry.getKey());a.addProperty("x",entry.getValue().x());a.addProperty("y",entry.getValue().y());a.addProperty("z",entry.getValue().z());anchors.add(a);
        }
        result.add("seats",anchors);
        JsonArray palette=new JsonArray();BuiltInRegistries.BLOCK.keySet().stream().filter(k->k.getNamespace().equals("minecraft")&&(VANILLA.contains(k.getPath())||architecturalBlock(k.getPath()))).sorted(Comparator.comparing(Object::toString)).forEach(k->palette.add(k.toString()));CUSTOM.stream().sorted().forEach(b->palette.add("agentcraft:"+b));result.add("palette",palette);
        long started=System.nanoTime();
        final int minX=-56,minY=60,minZ=-36,nx=109,ny=137,nz=155;
        var level=server.overworld();
        // Include foundations and the original cupola above the construction edit ceiling.
        // The bounded site is small enough to load directly, including rooms outside render distance.
        for(int cz=minZ>>4;cz<=(minZ+nz-1)>>4;cz++)
            for(int cx=minX>>4;cx<=(minX+nx-1)>>4;cx++)level.getChunk(cx,cz);
        int[] cells=new int[nx*ny*nz];Map<Cell,Integer> ids=new LinkedHashMap<>();
        JsonArray states=new JsonArray();states.add(JsonNull.INSTANCE); // 0 = air
        int solid=0;
        for(int y=0;y<ny;y++)for(int z=0;z<nz;z++)for(int x=0;x<nx;x++){
            Cell cell=read(level,new BlockPos(minX+x,minY+y,minZ+z));
            if(cell.state().isAir())continue;
            Integer id=ids.get(cell);
            if(id==null){id=ids.size()+1;ids.put(cell,id);JsonArray entry=new JsonArray();entry.add(cell.state().toString());entry.add(cell.binding());states.add(entry);}
            cells[(y*nz+z)*nx+x]=id;solid++;
        }
        JsonArray boxes=new JsonArray();
        for(int[] box:SurveyCuboids.pack(cells,nx,ny,nz)){
            JsonArray entry=new JsonArray();
            entry.add(box[0]+minX);entry.add(box[1]+minY);entry.add(box[2]+minZ);
            entry.add(box[3]+minX);entry.add(box[4]+minY);entry.add(box[5]+minZ);entry.add(box[6]);boxes.add(entry);
        }
        result.addProperty("region","x=-56..52, y=60..196, z=-36..118");
        result.addProperty("scope","entire headquarters construction site plus original house foundations and roof; independent of player location");
        result.addProperty("format","cuboids: [x1,y1,z1,x2,y2,z2,stateId], inclusive, non-overlapping; states[stateId]=[exact blockState,binding]. All omitted cells INSIDE region are verified air. Outside region is unknown. Read-only snapshot, not a screenshot or permission to overwrite.");
        result.add("states",states);result.add("cuboids",boxes);
        result.addProperty("scannedCells",cells.length);result.addProperty("nonAirCells",solid);
        result.addProperty("unloadedCells",0);result.addProperty("truncated",false);
        result.addProperty("available",true);result.addProperty("complete",true);
        result.addProperty("capturedAt",java.time.Instant.now().toString());
        result.addProperty("scanMillis",(System.nanoTime()-started)/1_000_000);
        return result;
    }

    private static void bounds(double x,double y,double z){if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||x < -56||x>52||y<64||y>192||z< -36||z>118)throw new IllegalArgumentException("Outside the headquarters construction area");}
    private static int integer(JsonElement e){double d=e.getAsDouble();if(!Double.isFinite(d)||d!=Math.rint(d))throw new IllegalArgumentException("Block coordinates must be integers");return (int)d;}
    private static BlockPos position(JsonArray p){if(p.size()!=3)throw new IllegalArgumentException("Expected x,y,z");int x=integer(p.get(0)),y=integer(p.get(1)),z=integer(p.get(2));bounds(x,y,z);return new BlockPos(x,y,z);}
    private static BlockState block(String text){
        if(text.length()>150||!text.matches("[a-z0-9_]+:[a-z0-9_]+(\\[[a-z0-9_=,]+\\])?"))throw new IllegalArgumentException("Invalid block state");
        String id=text.split("\\[")[0];String[] key=id.split(":");
        if(!(key[0].equals("minecraft")?(VANILLA.contains(key[1])||architecturalBlock(key[1])):key[0].equals("agentcraft")&&CUSTOM.contains(key[1])))throw new IllegalArgumentException("Unsupported construction block: "+id);
        BlockState result=BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath(key[0],key[1])).defaultBlockState();
        if(text.contains("["))for(String prop:text.substring(text.indexOf('[')+1,text.length()-1).split(",")){String[] pair=prop.split("=");if(pair.length!=2)throw new IllegalArgumentException("Invalid block property");Property<?> p=result.getBlock().getStateDefinition().getProperty(pair[0]);if(p==null)throw new IllegalArgumentException("Unknown block property");result=property(result,p,pair[1]);}
        return result;
    }
    private static boolean architecturalBlock(String id){
        if(!id.matches("[a-z0-9_]+"))return false;
        var key=Identifier.fromNamespaceAndPath("minecraft",id);
        if(!BuiltInRegistries.BLOCK.containsKey(key))return false;
        var state=BuiltInRegistries.BLOCK.getValue(key).defaultBlockState();
        if(state.hasBlockEntity())return false;
        return id.matches(".*_(planks|log|wood|stairs|slab|wall|fence|fence_gate|door|trapdoor|glass|glass_pane|concrete|concrete_powder|terracotta|wool|carpet|bricks|tiles|leaves)")
            ||id.contains("copper")||id.contains("quartz")||id.contains("sandstone")
            ||Set.of("stone","cobblestone","mossy_cobblestone","bricks","deepslate","cobbled_deepslate","polished_deepslate","tuff","calcite","diorite","andesite","granite","polished_diorite","polished_andesite","polished_granite","terracotta","iron_bars","chain","end_rod","tinted_glass","prismarine","dark_prismarine","smooth_basalt","blackstone","polished_blackstone","polished_tuff").contains(id);
    }
    private static <T extends Comparable<T>> BlockState property(BlockState state,Property<T> p,String value){return state.setValue(p,p.getValue(value).orElseThrow(()->new IllegalArgumentException("Invalid property value")));}
    private static Cell read(ServerLevel level,BlockPos p){
        var be=level.getBlockEntity(p);String binding=be instanceof StationBlockEntity station?station.binding():"";
        if(be instanceof SignBlockEntity sign)binding="label:"+String.join(" | ",sign.getText(SignTextSlot.FRONT).getMessages(false).stream().map(Component::getString).filter(t->!t.isEmpty()).toList());
        return new Cell(level.getBlockState(p),binding);
    }
    private static boolean matches(Cell a,Cell b){return a!=null&&b!=null&&Plan.sameDesign(a.state(),b.state())&&a.binding().equals(b.binding());}
    // Grass settles according to light/coverage after placement. Retain that live state in
    // our journal; this exception covers only grass/dirt with no block-entity binding.
    private static boolean settledSoil(Cell current,Cell intended){
        if(!current.binding().isEmpty()||!intended.binding().isEmpty())return false;
        return current.state().is(Blocks.DIRT)&&intended.state().is(Blocks.GRASS_BLOCK)
            ||current.state().is(Blocks.GRASS_BLOCK)&&intended.state().is(Blocks.DIRT);
    }
    private static boolean natural(BlockState b){String id=BuiltInRegistries.BLOCK.getKey(b.getBlock()).getPath();return b.isAir()||Set.of("grass_block","dirt","coarse_dirt","rooted_dirt","stone","gravel","sand","short_grass","tall_grass","fern","large_fern","dandelion","poppy","snow","moss_block","vine","dead_bush").contains(id)||id.endsWith("_leaves")||id.endsWith("_log");}
    private static void prepare(MinecraftServer server,JsonObject plan,boolean dryRun){
        JsonArray fills=plan.getAsJsonArray("fills");if(fills.size()>50000)throw new IllegalArgumentException("Too many fills");
        Map<BlockPos,Cell> desired=new LinkedHashMap<>();long count=0;
        for(JsonElement raw:fills){JsonObject f=raw.getAsJsonObject();BlockPos a=position(f.getAsJsonArray("from")),b=position(f.getAsJsonArray("to"));if(a.getX()>b.getX()||a.getY()>b.getY()||a.getZ()>b.getZ())throw new IllegalArgumentException("Reversed coordinates");count+=(long)(b.getX()-a.getX()+1)*(b.getY()-a.getY()+1)*(b.getZ()-a.getZ()+1);if(count>350000)throw new IllegalArgumentException("Build too large");BlockState block=block(f.get("block").getAsString());String binding=f.has("binding")&&!f.get("binding").isJsonNull()?f.get("binding").getAsString():"";if(!binding.isEmpty()){
            String kind=BuiltInRegistries.BLOCK.getKey(block.getBlock()).getPath();
            boolean valid=kind.equals("monitor")&&binding.matches("[a-z0-9-]{1,100}")||kind.equals("dark_oak_wall_sign")&&binding.startsWith("label:")&&binding.length()<=100;
            if(!valid)throw new IllegalArgumentException("Invalid monitor or room sign binding");
        }for(BlockPos p:BlockPos.betweenClosed(a,b))desired.put(p.immutable(),new Cell(block,binding));}
        // Panel connections are part of the durable intended state, not a later unjournaled edit.
        for(var entry:new ArrayList<>(desired.entrySet())){
            Cell c=entry.getValue();if(!(c.state().getBlock() instanceof PanelBlock))continue;
            BlockState b=c.state();Direction facing=b.getValue(PanelBlock.FACING);
            Direction[] dirs={Direction.UP,Direction.DOWN,facing.getClockWise(),facing.getCounterClockWise()};
            var props=List.of(PanelBlock.UP,PanelBlock.DOWN,PanelBlock.LEFT,PanelBlock.RIGHT);
            for(int i=0;i<dirs.length;i++){BlockPos pos=entry.getKey().relative(dirs[i]);Cell neighbor=desired.getOrDefault(pos,read(server.overworld(),pos));boolean joins=neighbor.state().is(b.getBlock())&&neighbor.state().getValue(PanelBlock.FACING)==facing;b=b.setValue(props.get(i),joins);}
            desired.put(entry.getKey(),new Cell(b,c.binding()));
        }
        Map<String,Anchor> nextSeats=new LinkedHashMap<>(seats);JsonArray arr=plan.getAsJsonArray("seats");if(arr.size()>128)throw new IllegalArgumentException("Too many desks");
        for(JsonElement raw:arr){JsonObject a=raw.getAsJsonObject();String id=a.get("id").getAsString();if(!id.matches("[a-z0-9-]{1,100}"))throw new IllegalArgumentException("Invalid desk identity");double x=a.get("x").getAsDouble(),y=a.get("y").getAsDouble(),z=a.get("z").getAsDouble();bounds(x,y,z);float yaw=a.get("yaw").getAsFloat();if(!Float.isFinite(yaw))throw new IllegalArgumentException("Invalid rotation");nextSeats.put(id,new Anchor("desk_"+id,x,y,z,yaw,0));}
        List<Edit> next=new ArrayList<>();ServerLevel level=server.overworld();var legacy=OfficeConstruction.ownedBlocks();
        // Only extensions outside the original hall plus its three explicit doorway cuts can reuse
        // generated landscaping. Matching the original saved plan preserves later player changes.
        int[] box=StudioHqBuilder.SITE;int sx=box[3]-box[0]+1,sz=box[5]-box[2]+1;
        BlockState[] original=PlanStore.load(server,StudioHqBuilder.ID,box,sx*sz*(box[4]-box[1]+1));
        if(original!=null)for(BlockPos p:desired.keySet()){
            boolean extension=p.getX()<=-24||p.getX()>=24||p.getZ()<=-11;
            boolean doorway=Math.abs(p.getX())<=1&&p.getZ()==-10&&p.getY()>=66&&p.getY()<=70;
            if((extension||doorway)&&p.getX()>=box[0]&&p.getX()<=box[3]&&p.getY()>=box[1]&&p.getY()<=box[4]&&p.getZ()>=box[2]&&p.getZ()<=box[5])legacy.put(p,original[((p.getY()-box[1])*sz+p.getZ()-box[2])*sx+p.getX()-box[0]]);
        }
        for(var e:desired.entrySet()){Cell before=read(level,e.getKey()),after=e.getValue();if(matches(before,after))continue;boolean ours=matches(owned.get(e.getKey()),before);if(!ours&&!(legacy.containsKey(e.getKey())&&Plan.sameDesign(before.state(),legacy.get(e.getKey())))&&(!natural(before.state())||level.getBlockEntity(e.getKey())!=null))throw new IllegalStateException("Player construction protected at "+e.getKey());if(level.getBlockEntity(e.getKey())!=null&&!ours)throw new IllegalStateException("Existing block entity protected at "+e.getKey());next.add(new Edit(e.getKey(),before,after,ours));}
        next.sort(Comparator.comparingInt((Edit e)->e.after().state().isAir()?0:e.pos().getY()<=65?1:2).thenComparingInt(e->e.pos().getY()));
        String nextTitle=plan.get("title").getAsString();if(nextTitle.length()>180)throw new IllegalArgumentException("Title too long");
        if(dryRun)return;
        edits.clear();edits.addAll(next);previousSeats=new LinkedHashMap<>(seats);seats=nextSeats;title=nextTitle;cursor=0;total=edits.size();state=total==0?"complete":"building";error="";save(server);publishSeats();
        var old=Anchors.current();var b=old.bounds();Anchors.publish(server,new Anchors.Layout(old.name(),old.revision(),new Anchors.Bounds(-56,Math.min(64,b.minY()),Math.min(-36,b.minZ()),52,Math.max(192,b.maxY()),118),old.anchors()));
    }
    private static void put(ServerLevel l,BlockPos p,Cell c){l.setBlock(p,c.state(),Plan.FLAGS);if(l.getBlockEntity(p) instanceof StationBlockEntity station)station.setBinding(c.binding());
        if(l.getBlockEntity(p) instanceof SignBlockEntity sign&&c.binding().startsWith("label:")){
            String[] lines=c.binding().substring(6).split("\\s*\\|\\s*");List<Component> text=new ArrayList<>();
            for(int i=0;i<4;i++)text.add(Component.literal(i<lines.length?lines[i]:""));
            sign.setText(new SignText(text,text,DyeColor.WHITE,true),SignTextSlot.FRONT);sign.setChanged();l.sendBlockUpdated(p,c.state(),c.state(),Block.UPDATE_CLIENTS);
        }
    }
    private static void tick(MinecraftServer server){
        if(!building())return;boolean undo=state.equals("undoing");
        try{
            for(int n=0;n<24;n++){
                if(undo?cursor<=0:cursor>=edits.size()){state=undo?"undone":"complete";if(undo)seats=new LinkedHashMap<>(previousSeats);publishSeats();save(server);return;}
                Edit e=edits.get(undo?cursor-1:cursor);builderX=Math.max(-42,Math.min(42,e.pos().getX()))+0.5;builderZ=e.pos().getZ();Cell current=read(server.overworld(),e.pos()),expected=undo?e.after():e.before(),after=undo?e.before():e.after();
                if(!undo&&settledSoil(current,after)){
                    after=current;e=new Edit(e.pos(),e.before(),after,e.ownedBefore());
                    edits.set(cursor,e);
                }
                // Grass can spread onto preflighted dirt while a large build is in progress.
                boolean grassSpread=!undo&&!e.ownedBefore()&&expected.state().is(Blocks.DIRT)&&current.state().is(Blocks.GRASS_BLOCK)&&current.binding().isEmpty();
                String priorId=BuiltInRegistries.BLOCK.getKey(expected.state().getBlock()).getPath();
                boolean plantDecay=!undo&&!e.ownedBefore()&&current.state().isAir()&&expected.binding().isEmpty()
                    &&(priorId.endsWith("_leaves")||Set.of("leaf_litter","short_grass","tall_grass","fern","large_fern","dandelion","poppy","vine","dead_bush").contains(priorId));
                if(matches(current,expected)||grassSpread||plantDecay)put(server.overworld(),e.pos(),after);
                else if(!matches(current,after)&&!undo){state="blocked";error="Block changed at "+e.pos()+". Restore it or undo this build.";save(server);return;}
                if(undo){if(e.ownedBefore())owned.put(e.pos(),e.before());else owned.remove(e.pos());cursor--;}
                else{owned.put(e.pos(),e.after());cursor++;}
            }
            if(ticks%40==0)save(server);
        }catch(Exception e){state=undo?"undo-paused":"paused";error="Construction paused: "+e.getMessage();AgentCraft.LOGGER.error(error,e);}
    }
    private static void publishSeats(){visibleSeats=Map.copyOf(seats);}
    private static Path file(MinecraftServer s){return s.getWorldPath(LevelResource.ROOT).resolve("headquarters-builder.nbt");}
    private static CompoundTag cell(Cell c){CompoundTag t=new CompoundTag();t.put("block",NbtUtils.writeBlockState(c.state()));t.putString("binding",c.binding());return t;}
    private static Cell cell(CompoundTag t){return new Cell(NbtUtils.readBlockState(BuiltInRegistries.BLOCK,t.getCompoundOrEmpty("block")),t.getStringOr("binding",""));}
    private static ListTag seats(Map<String,Anchor> values){ListTag list=new ListTag();for(var e:values.entrySet()){CompoundTag t=new CompoundTag();Anchor a=e.getValue();t.putString("id",e.getKey());t.putDouble("x",a.x());t.putDouble("y",a.y());t.putDouble("z",a.z());t.putFloat("yaw",a.yaw());list.add(t);}return list;}
    private static Map<String,Anchor> seats(ListTag list){Map<String,Anchor> result=new LinkedHashMap<>();for(int i=0;i<list.size();i++){CompoundTag t=list.getCompoundOrEmpty(i);String id=t.getStringOr("id","");result.put(id,new Anchor("desk_"+id,t.getDoubleOr("x",0),t.getDoubleOr("y",66),t.getDoubleOr("z",60),t.getFloatOr("yaw",0),0));}return result;}
    private static void save(MinecraftServer s){
        CompoundTag root=new CompoundTag();root.putString("state",state);root.putString("title",title);root.putString("error",error);root.putInt("cursor",cursor);ListTag list=new ListTag();
        for(Edit e:edits){CompoundTag t=new CompoundTag();t.putLong("pos",e.pos().asLong());t.put("before",cell(e.before()));t.put("after",cell(e.after()));t.putBoolean("ownedBefore",e.ownedBefore());list.add(t);}root.put("edits",list);list=new ListTag();for(var e:owned.entrySet()){CompoundTag t=cell(e.getValue());t.putLong("pos",e.getKey().asLong());list.add(t);}root.put("owned",list);root.put("seats",seats(seats));root.put("previousSeats",seats(previousSeats));
        try{Path f=file(s),tmp=f.resolveSibling("headquarters-builder.tmp");NbtIo.writeCompressed(root,tmp);if(Files.exists(f))Files.copy(f,f.resolveSibling("headquarters-builder.bak"),StandardCopyOption.REPLACE_EXISTING);Files.move(tmp,f,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(Exception e){state=state.equals("undoing")?"undo-paused":"paused";throw new IllegalStateException("Cannot save build journal",e);}
    }
    private static void load(MinecraftServer s){
        if(!Files.exists(file(s)))return;
        try{CompoundTag root=NbtIo.readCompressed(file(s),NbtAccounter.unlimitedHeap());state=root.getStringOr("state","idle");if(state.equals("building"))state="paused";else if(state.equals("undoing"))state="undo-paused";title=root.getStringOr("title","Builder");error=root.getStringOr("error","");ListTag list=root.getListOrEmpty("edits");for(int i=0;i<list.size();i++){var t=list.getCompoundOrEmpty(i);edits.add(new Edit(BlockPos.of(t.getLongOr("pos",0)),cell(t.getCompoundOrEmpty("before")),cell(t.getCompoundOrEmpty("after")),t.getBooleanOr("ownedBefore",false)));}total=edits.size();cursor=Math.max(0,Math.min(total,root.getIntOr("cursor",0)));list=root.getListOrEmpty("owned");for(int i=0;i<list.size();i++){var t=list.getCompoundOrEmpty(i);owned.put(BlockPos.of(t.getLongOr("pos",0)),cell(t));}seats=seats(root.getListOrEmpty("seats"));previousSeats=seats(root.getListOrEmpty("previousSeats"));publishSeats();
            if(state.equals("complete")||state.equals("paused")||state.equals("blocked")){
                for(int i=0;i<cursor;i++){
                    Edit e=edits.get(i);Cell current=read(s.overworld(),e.pos());
                    if(settledSoil(current,e.after())){
                        edits.set(i,new Edit(e.pos(),e.before(),current,e.ownedBefore()));
                        owned.put(e.pos(),current);
                    }else if(!matches(current,e.after())){
                        cursor=i;state="paused";error="World and build journal need reconciliation; resume to verify remaining cells.";break;
                    }
                }
            }
        }catch(Exception e){reset();state="blocked";error="Could not read build journal";AgentCraft.LOGGER.error(error,e);}
    }
}
