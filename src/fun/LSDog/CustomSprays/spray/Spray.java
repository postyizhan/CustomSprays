package fun.LSDog.CustomSprays.spray;

import fun.LSDog.CustomSprays.CustomSprays;
import fun.LSDog.CustomSprays.PlayerSprayEvent;
import fun.LSDog.CustomSprays.data.DataManager;
import fun.LSDog.CustomSprays.map.MapViewId;
import fun.LSDog.CustomSprays.util.NMS;
import fun.LSDog.CustomSprays.util.RayTracer;
import fun.LSDog.CustomSprays.util.RegionChecker;
import fun.LSDog.CustomSprays.util.VaultChecker;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * 喷漆本体，包括所有的反射发包方法
 */
public class Spray {

    public final Player player;
    protected final World world;
    protected final byte[] pixels;
    protected final Set<Player> playersShown;

    public Block block;
    public Location location;
    public BlockFace blockFace;
    protected Location playerLocation;
    protected int intDirection;

    protected int itemFrameId = -1;
    protected boolean valid = true;

    /**
     * The constructor of a spray.
     * Use {@link #create(long)} to initialize and calculate the spray, use {@link #spawn(Collection, boolean)} to send packet.
     * @param player The sprayer
     * @param pixels Byte color array <b>size of 128*128</b>
     * @param showTo The players who can see this spray (in spraying).
     */
    public Spray(Player player, byte[] pixels, Collection<? extends Player> showTo) {
        this.player = player;
        this.world = player.getWorld();
        this.pixels = pixels;
        this.playersShown = new HashSet<>(showTo);
    }

    /**
     * Do raytrace and find the surface for spraying, then spawn the spray immediately
     * @param removeTick Automatic removal duration, negative numbers == no auto remove
     */
    public boolean create(long removeTick) {

        Location eyeLocation = player.getEyeLocation();
        RayTracer.BlockRayTraceResult ray =
                new RayTracer(eyeLocation.getDirection(), eyeLocation, CustomSprays.plugin.getConfig().getDouble("distance")).rayTraceBlock(SprayManager::isSpraySurfaceBlock);
        if (ray == null) return false;

        // 禁止在1.13以下, 在方块上下面喷漆
        if (ray.isUpOrDown() && ( NMS.getSubVer() < 13 || !CustomSprays.plugin.getConfig().getBoolean("spray_on_ground") )) return false;

        this.block = ray.getRelativeBlock();
        this.location = block.getLocation();
        this.blockFace = ray.blockFace;
        this.playerLocation = player.getLocation();
        this.intDirection = MapFrameFactory.blockFaceToIntDirection(blockFace);

        // ↓喷漆占用就取消
        if (SprayManager.hasSpray(ray.getRelativeBlock(), blockFace)) return false;

        // 喷漆在禁止区域就取消
        if (!player.hasPermission("CustomSprays.nodisable") && RegionChecker.isLocInDisabledRegion(location)) {
            player.sendMessage(CustomSprays.prefix + DataManager.getMsg(player, "SPRAY.DISABLED_REGION"));
            return false;
        }

        double cost = CustomSprays.plugin.getConfig().getDouble((this instanceof SprayBig) ? "spray_big_cost" : "spray_cost");
        if (cost != 0 && !player.hasPermission("CustomSprays.nomoney") && VaultChecker.isVaultEnabled()) {
            if (VaultChecker.costMoney(player, cost)) {
                player.sendMessage(CustomSprays.prefix + DataManager.getMsg(player, "SPRAY.COST").replace("%cost%", cost+""));
            } else {
                player.sendMessage(CustomSprays.prefix + DataManager.getMsg(player, "SPRAY.NO_MONEY").replace("%cost%", cost+""));
                return false;
            }
        }

        // Call PlayerSprayEvent
        PlayerSprayEvent event = new PlayerSprayEvent(player, this);
        Future<Boolean> result = Bukkit.getScheduler().callSyncMethod(CustomSprays.plugin, () -> {
            Bukkit.getPluginManager().callEvent(event);
            return event.isCancelled();
        });
        try {
            if (result.get()) {
                valid = false;
                return false;
            }
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }

        try {
            spawn(playersShown, true);
        } catch (Throwable e) {
            e.printStackTrace();
            return false;
        }

        SprayManager.addSpray(this);
        if (removeTick >= 0) autoRemove(removeTick);

        return true;
    }

    /**
     * 生成展示框与地图
     * @param playersShowTo 要展示给的玩家, null为默认初始值
     */
    public void spawn(Collection<? extends Player> playersShowTo, boolean playSound) throws Throwable {

        if (!valid) return;

        int mapViewId = MapViewId.getId();

        Object mcMap = MapFrameFactory.getMcMap(mapViewId);
        Location offLoc = NMS.getSubVer() >= 8 ? location : location.add(-blockFace.getModX(), 0, -blockFace.getModZ());
        Object itemFrame = MapFrameFactory.getItemFrame(mcMap, offLoc, blockFace, playerLocation);
        Object spawnPacket = MapFrameFactory.getSpawnPacket(itemFrame, intDirection);
        if (NMS.getSubVer() <= 7) NMS.setSpawnPacketLocation(spawnPacket, offLoc);
        Object mapPacket = null;
        Object[] mapPackets_7 = new Object[0];
        if (NMS.getSubVer() >= 8) mapPacket = MapFrameFactory.getMapPacket(mapViewId, pixels);
        else mapPackets_7 = MapFrameFactory.getMapPackets_7((short) mapViewId, pixels);

        if (itemFrameId == -1) itemFrameId = NMS.getMcEntityId(itemFrame);
        Object dataPacket = NMS.getPacketPlayOutEntityMetadata(itemFrame);

        Collection<? extends Player> $playersShowTo = playersShown;

        if (playersShowTo != null) {
            $playersShowTo = playersShowTo;
            playersShown.addAll($playersShowTo); // 重新生成的也要加到可见玩家里
        }

        for (Player p : $playersShowTo) {
            NMS.sendPacket(p, spawnPacket);  // 生成带地图的展示框
            NMS.sendPacket(p, dataPacket);  // 为展示框添加 dataWatcher
            // 刷新 mapView (也就是"画图")
            if (NMS.getSubVer() >= 8) NMS.sendPacket(p, mapPacket);
            else for (Object packet : mapPackets_7) NMS.sendPacket(p, packet);
        }

        if (playSound) playSpraySound(player);

    }

    /**
     * 自动自毁
     * @param tick 延迟Tick后自毁
     */
    public void autoRemove(long tick) {
        Bukkit.getScheduler().runTaskLaterAsynchronously(CustomSprays.plugin, this::remove, tick);
    }

    /**
     * 自毁
     */
    public void remove() {
        if (!valid) return;
        valid = false;
        SprayManager.removeSpray(this);
        NMS.sendDestroyEntities(new int[]{itemFrameId}, playersShown);
    }

    /**
     * 向某个玩家播放喷漆音效
     */
    public static void playSpraySound(Player player) {
        String soundName = CustomSprays.plugin.getConfig().getString("spray_sound");
        if (soundName == null || "default".equals(soundName)) {
            Sound sound = Sound.valueOf(NMS.getSubVer() <= 8 ? "SILVERFISH_HIT" : "ENTITY_SILVERFISH_HURT");
            Bukkit.getScheduler().runTask(CustomSprays.plugin, () ->
                    player.getWorld().playSound(player.getLocation(), sound, 1, 0.8F));
        } else {
            String[] strings = soundName.split("-");
            if (strings.length != 3) return;
            Bukkit.getScheduler().runTask(CustomSprays.plugin, () ->
                    player.getWorld().playSound(player.getLocation(), strings[0], Float.parseFloat(strings[1]), Float.parseFloat(strings[2])));
        }
    }

    /**
     * 移除喷漆音效
     */
    public static void playRemoveSound(Player player) {
        int subVer = NMS.getSubVer();
        String soundName;
        if (subVer <= 8) soundName = "DIG_WOOL";
        else if (subVer <= 12) soundName = "BLOCK_CLOTH_HIT";
        else soundName = "BLOCK_WOOL_HIT";
        Sound sound = Sound.valueOf(soundName);
        Bukkit.getScheduler().runTask(CustomSprays.plugin, () ->
                player.getWorld().playSound(player.getLocation(), sound, 1, 1.2F));

    }

}
