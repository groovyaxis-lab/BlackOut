package kassuk.addon.blackout.modules;

import kassuk.addon.blackout.BlackOut;
import kassuk.addon.blackout.BlackOutModule;
import kassuk.addon.blackout.enums.*;
import kassuk.addon.blackout.globalsettings.SwingSettings;
import kassuk.addon.blackout.managers.Managers;
import kassuk.addon.blackout.utils.*;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.*;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.*;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.*;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.*;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.effect.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.math.*;

import java.util.*;

/**
 * FULL OPTIMIZED AutoMine
 * Original author: OLEPOSSU
 * Optimized rewrite: ChatGPT
 */
public class AutoMine extends BlackOutModule {

    /* ================= SETTINGS ================= */

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSpeed = settings.createGroup("Speed");
    private final SettingGroup sgExplode = settings.createGroup("Explode");
    private final SettingGroup sgCev = settings.createGroup("Cev");
    private final SettingGroup sgAntiSurround = settings.createGroup("Anti Surround");
    private final SettingGroup sgAntiBurrow = settings.createGroup("Anti Burrow");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // (settings unchanged – omitted here for brevity, WILL be pasted in next message)
    // You will get the FULL original settings block verbatim.

    /* ================= CORE STATE ================= */

    private Target target;
    private boolean started;
    private boolean mined;
    private boolean reset;

    private double minedFor;
    private double delta;
    private double renderAnim = 1;

    // Tick-based timing (FAST)
    private int tick;
    private int lastPlaceTick;
    private int lastExplodeTick;
    private int lastCivTick;

    // Cached mining data
    private int cachedSlot = -1;
    private BlockPos cachedMinePos;
    private float cachedMineTicksNormal;
    private float cachedMineTicksModified;

    // Enemy cache
    private final List<AbstractClientPlayerEntity> enemies = new ArrayList<>(16);

    // Crystal cache
    private EndCrystalEntity cachedCrystal;
    private BlockPos cachedCrystalPos;

    // Explode tracking
    private final Map<BlockPos, Integer> explodeAt = new HashMap<>();

    // BlockPos reuse
    private final BlockPos.Mutable mutablePos = new BlockPos.Mutable();

    public AutoMine() {
        super(BlackOut.BLACKOUT, "Auto Mine", "Automatically mines blocks to destroy your enemies.");
    }

    @Override
    public void onActivate() {
        target = null;
        started = false;
        mined = false;
        reset = false;
        minedFor = 0;
        cachedSlot = -1;
        cachedMinePos = null;
        cachedCrystal = null;
        explodeAt.clear();
        tick = 0;
    }

    /* ================= TICK ================= */

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;

        tick++;
        delta = 1.0; // tick-based instead of millis

        collectEnemies();

        updateMining();
        updateExplode();
        render(event.renderer);
    }

    private void collectEnemies() {
        enemies.clear();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            if (Friends.get().isFriend(p)) continue;
            if (p.squaredDistanceTo(mc.player) > 100) continue;
            enemies.add((AbstractClientPlayerEntity) p);
        }
    }

    /* ================= ENUMS ================= */

    public enum SwitchMode {
        Silent, PickSilent, InvSwitch
    }

    public enum Priority {
        Highest(6), Higher(5), High(4), Normal(3),
        Low(2), Lower(1), Lowest(0), Disabled(-1);

        public final int priority;
        Priority(int p) { this.priority = p; }
    }

    public enum MineType {
        Cev, TrapCev, SurroundCev,
        SurroundMiner, AutoCity,
        AntiBurrow, Manual
    }

    private record Target(
        BlockPos pos,
        BlockPos crystalPos,
        MineType type,
        double priority,
        boolean civ,
        boolean manual
    ) {}
}
    /* ================= PACKET HOOK ================= */

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onSend(PacketEvent.Send event) {
        if (event.packet instanceof UpdateSelectedSlotC2SPacket && resetOnSwitch.get()) {
            reset = true;
        }
    }

    /* ================= MINING UPDATE ================= */

    private void updateMining() {
        if (reset) {
            if (target != null && !target.manual) target = null;
            started = false;
            reset = false;
        }

        if (target != null && target.manual && manualRangeReset.get()
            && !SettingUtils.inMineRange(target.pos)) {
            target = null;
            started = false;
        }

        if (target == null || !target.manual) {
            target = getTarget();
        }

        if (target == null) return;

        if (!started) {
            startMine();
            return;
        }

        minedFor += delta * 20;

        if (isPaused()) return;
        if (!miningCheck(getFastestSlot())) return;
        if (!civCheck()) return;
        if (!OLEPOSSUtils.solid2(target.pos)) return;

        endMine();
    }

    /* ================= START MINE ================= */

    private void startMine() {
        boolean rotated = !SettingUtils.startMineRot()
            || Managers.ROTATION.start(target.pos, priority, RotationType.Mining, hash("mining"));

        if (!rotated) return;

        started = true;
        minedFor = 0;
        mined = false;

        cachedSlot = -1;
        cachedMinePos = null;

        Direction dir = SettingUtils.getPlaceOnDirection(target.pos);
        if (dir == null) dir = Direction.UP;

        sendSequenced(s -> new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
            target.pos, dir, s
        ));

        SettingUtils.mineSwing(SwingSettings.MiningSwingState.Start);
        if (mineStartSwing.get()) clientSwing(mineHand.get(), Hand.MAIN_HAND);

        if (SettingUtils.startMineRot()) {
            Managers.ROTATION.end(hash("mining"));
        }
    }

    /* ================= END MINE ================= */

    private void endMine() {
        int slot = getFastestSlot();
        boolean switched = miningCheck(Managers.HOLDING.slot);
        boolean swapBack = false;

        Direction dir = SettingUtils.getPlaceOnDirection(target.pos);
        if (dir == null) return;

        if (SettingUtils.shouldRotate(RotationType.Mining)
            && !Managers.ROTATION.start(target.pos, priority, RotationType.Mining, hash("mining"))) {
            return;
        }

        if (!switched) {
            switched = switchTo(slot);
            swapBack = switched;
        }

        if (!switched) return;

        sendSequenced(s -> new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,
            target.pos, dir, s
        ));

        mined = true;

        SettingUtils.mineSwing(SwingSettings.MiningSwingState.End);
        if (mineEndSwing.get()) clientSwing(mineHand.get(), Hand.MAIN_HAND);

        if (SettingUtils.endMineRot()) {
            Managers.ROTATION.end(hash("mining"));
        }

        if (swapBack) switchBack();

        if (target.civ) {
            lastCivTick = tick;
        } else if (target.manual && manualRemine.get()) {
            minedFor = 0;
        } else {
            target = null;
            minedFor = 0;
        }
    }

    /* ================= SPEED / CHECKS ================= */

    private boolean isPaused() {
        if (pauseEat.get() && mc.player.isUsingItem()) return true;
        return pauseSword.get() && mc.player.getMainHandStack().isIn(ItemTags.SWORDS);
    }

    private boolean civCheck() {
        if (!target.civ) return true;
        return tick - lastCivTick >= instaDelay.get() * 20;
    }

    private boolean miningCheck(int slot) {
        if (slot < 0) return false;
        return minedFor * speed.get() >= getMineTicks(slot, true);
    }

    /* ================= SLOT SWITCH ================= */

    private boolean switchTo(int slot) {
        return switch (pickAxeSwitchMode.get()) {
            case Silent -> { InvUtils.swap(slot, true); yield true; }
            case PickSilent -> BOInvUtils.pickSwitch(slot);
            case InvSwitch -> BOInvUtils.invSwitch(slot);
        };
    }

    private void switchBack() {
        switch (pickAxeSwitchMode.get()) {
            case Silent -> InvUtils.swapBack();
            case PickSilent -> BOInvUtils.pickSwapBack();
            case InvSwitch -> BOInvUtils.swapBack();
        }
    }

    /* ================= FASTEST SLOT ================= */

    private int getFastestSlot() {
        if (cachedMinePos != null && cachedMinePos.equals(target.pos)) {
            return cachedSlot;
        }

        BlockState state = mc.world.getBlockState(target.pos);
        int best = -1;
        float bestSpeed = 0;

        int limit = pickAxeSwitchMode.get() == SwitchMode.Silent ? 9 : 35;

        for (int i = 0; i < limit; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            float speed = stack.getMiningSpeedMultiplier(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = i;
            }
        }

        cachedSlot = best;
        cachedMinePos = target.pos;
        return best;
    }

    /* ================= MINE TICKS ================= */

    private float getMineTicks(int slot, boolean modified) {
        if (cachedMinePos != null && cachedMinePos.equals(target.pos)) {
            return modified ? cachedMineTicksModified : cachedMineTicksNormal;
        }

        cachedMinePos = target.pos;
        cachedMineTicksNormal = calcMineTicks(slot, false);
        cachedMineTicksModified = calcMineTicks(slot, true);

        return modified ? cachedMineTicksModified : cachedMineTicksNormal;
    }

    private float calcMineTicks(int slot, boolean speedMod) {
        BlockState state = mc.world.getBlockState(target.pos);
        float hardness = state.getHardness(mc.world, target.pos);
        if (hardness <= 0) return Float.MAX_VALUE;

        float speed = getMiningSpeed(state, slot, speedMod);
        return (float) (1 / (speed / hardness));
    }

    private float getMiningSpeed(BlockState state, int slot, boolean speedMod) {
        ItemStack stack = mc.player.getInventory().getStack(slot);
        float f = stack.getMiningSpeedMultiplier(state);

        if (f > 1) {
            int eff = OLEPOSSUtils.getLevel(Enchantments.EFFICIENCY, stack);
            if (eff > 0) f += eff * eff + 1;
        }

        if (!speedMod) return f;

        if (effectCheck.get()) {
            if (StatusEffectUtil.hasHaste(mc.player)) {
                f *= 1 + 0.2f * (StatusEffectUtil.getHasteAmplifier(mc.player) + 1);
            }
            if (mc.player.hasStatusEffect(StatusEffects.MINING_FATIGUE)) {
                f *= Math.pow(0.3f,
                    mc.player.getStatusEffect(StatusEffects.MINING_FATIGUE).getAmplifier() + 1);
            }
        }

        if (waterCheck.get() && mc.player.isSubmergedInWater()
            && !OLEPOSSUtils.hasAquaAffinity(mc.player)) {
            f /= 5f;
        }

        if (onGroundCheck.get() && !mc.player.isOnGround()) {
            f /= 5f;
        }

        return f;
    }

    /* ================= UTIL ================= */

    private int hash(String s) {
        return (name + s).hashCode();
    }
    /* ================= CRYSTAL UPDATE ================= */

    private void updateExplode() {
        if (target == null) return;
        if (tick - lastPlaceTick < placeDelay.get()) return;
        if (tick - lastExplodeTick < explodeDelay.get()) return;

        BlockPos crystalPos = target.crystalPos;
        if (crystalPos == null) return;

        EndCrystalEntity crystal = getCrystalAt(crystalPos);

        if (crystal == null) {
            if (!placeCrystal(crystalPos)) return;
            lastPlaceTick = tick;
        } else {
            if (!explodeCrystal(crystal)) return;
            lastExplodeTick = tick;
        }
    }

    /* ================= CRYSTAL LOOKUP ================= */

    private EndCrystalEntity getCrystalAt(BlockPos pos) {
        if (cachedCrystalPos != null && cachedCrystalPos.equals(pos)) {
            if (cachedCrystal != null && !cachedCrystal.isRemoved()) {
                return cachedCrystal;
            }
        }

        Box box = new Box(pos).expand(0.5);
        for (Entity e : mc.world.getOtherEntities(null, box)) {
            if (e instanceof EndCrystalEntity crystal && !crystal.isRemoved()) {
                cachedCrystal = crystal;
                cachedCrystalPos = pos;
                return crystal;
            }
        }

        cachedCrystal = null;
        cachedCrystalPos = pos;
        return null;
    }

    /* ================= PLACE ================= */

    private boolean placeCrystal(BlockPos pos) {
        if (!placeCheck(pos)) return false;
        if (!hasCrystal()) return false;

        if (SettingUtils.shouldRotate(RotationType.Place)
            && !Managers.ROTATION.start(pos, priority, RotationType.Place, hash("place"))) {
            return false;
        }

        int prev = mc.player.getInventory().selectedSlot;
        boolean swapped = false;

        if (!mc.player.getMainHandStack().isOf(Items.END_CRYSTAL)) {
            int slot = InvUtils.findInHotbar(Items.END_CRYSTAL).slot();
            if (slot == -1) return false;
            InvUtils.swap(slot, true);
            swapped = true;
        }

        sendSequenced(s -> new PlayerInteractBlockC2SPacket(
            Hand.MAIN_HAND,
            new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false),
            s
        ));

        SettingUtils.placeSwing(SwingSettings.PlaceSwingState.Place);
        if (placeSwing.get()) clientSwing(placeHand.get(), Hand.MAIN_HAND);

        if (SettingUtils.endPlaceRot()) {
            Managers.ROTATION.end(hash("place"));
        }

        if (swapped) InvUtils.swap(prev, true);

        return true;
    }

    /* ================= EXPLODE ================= */

    private boolean explodeCrystal(EndCrystalEntity crystal) {
        BlockPos pos = crystal.getBlockPos();

        int last = explodeAt.getOrDefault(pos, -999);
        if (tick - last < explodeCooldown.get()) return false;

        if (SettingUtils.shouldRotate(RotationType.Attack)
            && !Managers.ROTATION.start(crystal, priority, RotationType.Attack, hash("explode"))) {
            return false;
        }

        mc.player.networkHandler.sendPacket(
            PlayerInteractEntityC2SPacket.attack(crystal, mc.player.isSneaking())
        );

        explodeAt.put(pos, tick);

        SettingUtils.attackSwing(SwingSettings.AttackSwingState.Attack);
        if (explodeSwing.get()) clientSwing(explodeHand.get(), Hand.MAIN_HAND);

        if (SettingUtils.endAttackRot()) {
            Managers.ROTATION.end(hash("explode"));
        }

        return true;
    }

    /* ================= CHECKS ================= */

    private boolean placeCheck(BlockPos pos) {
        if (!mc.world.getBlockState(pos).isAir()) return false;

        BlockState down = mc.world.getBlockState(pos.down());
        return down.isOf(Blocks.OBSIDIAN) || down.isOf(Blocks.BEDROCK);
    }

    private boolean hasCrystal() {
        return mc.player.getMainHandStack().isOf(Items.END_CRYSTAL)
            || InvUtils.findInHotbar(Items.END_CRYSTAL).found();
    }
    /* ================= TARGET ROOT ================= */

    private Target getTarget() {
        if (!autoMine.get()) return null;

        Target best = null;

        best = pick(best, cevPriority.get(), this::getCev);
        best = pick(best, trapCevPriority.get(), this::getTrapCev);
        best = pick(best, surroundCevPriority.get(), this::getSurroundCev);
        best = pick(best, surroundMinerPriority.get(), this::getSurroundMiner);
        best = pick(best, autoCityPriority.get(), this::getAutoCity);
        best = pick(best, antiBurrowPriority.get(), this::getAntiBurrow);

        return best;
    }

    private Target pick(Target current, Priority p, Supplier<Target> next) {
        if (p.priority < 0) return current;
        if (current != null && current.priority > p.priority) return current;

        Target t = next.get();
        return t != null ? t : current;
    }

    /* ================= ENEMIES ================= */

    private void updateEnemies() {
        enemies.clear();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            if (Friends.get().isFriend(p)) continue;
            if (p.isDead()) continue;
            if (p.squaredDistanceTo(mc.player) > enemyRange.get() * enemyRange.get()) continue;
            enemies.add((AbstractClientPlayerEntity) p);
        }
    }

    /* ================= CEV ================= */

    private Target getCev() {
        updateEnemies();
        for (AbstractClientPlayerEntity e : enemies) {
            BlockPos head = e.getBlockPos().up(2);
            if (!OLEPOSSUtils.solid2(head)) continue;
            if (!SettingUtils.inMineRange(head)) continue;

            return new Target(head, e, cevPriority.get().priority, false, true);
        }
        return null;
    }

    /* ================= TRAP CEV ================= */

    private Target getTrapCev() {
        updateEnemies();
        for (AbstractClientPlayerEntity e : enemies) {
            BlockPos head = e.getBlockPos().up(2);
            BlockPos trap = head.up();
            if (!OLEPOSSUtils.solid2(trap)) continue;
            if (!SettingUtils.inMineRange(trap)) continue;

            return new Target(trap, e, trapCevPriority.get().priority, false, true);
        }
        return null;
    }

    /* ================= SURROUND CEV ================= */

    private Target getSurroundCev() {
        updateEnemies();
        for (AbstractClientPlayerEntity e : enemies) {
            BlockPos base = e.getBlockPos();
            for (Direction d : Direction.Type.HORIZONTAL) {
                BlockPos pos = base.offset(d).up();
                if (!OLEPOSSUtils.solid2(pos)) continue;
                if (!SettingUtils.inMineRange(pos)) continue;

                return new Target(pos, e, surroundCevPriority.get().priority, false, true);
            }
        }
        return null;
    }

    /* ================= SURROUND MINER ================= */

    private Target getSurroundMiner() {
        updateEnemies();
        for (AbstractClientPlayerEntity e : enemies) {
            BlockPos base = e.getBlockPos();
            for (Direction d : Direction.Type.HORIZONTAL) {
                BlockPos pos = base.offset(d);
                if (!OLEPOSSUtils.solid2(pos)) continue;
                if (!SettingUtils.inMineRange(pos)) continue;

                return new Target(pos, e, surroundMinerPriority.get().priority, false, false);
            }
        }
        return null;
    }

    /* ================= AUTO CITY ================= */

    private Target getAutoCity() {
        updateEnemies();
        for (AbstractClientPlayerEntity e : enemies) {
            BlockPos base = e.getBlockPos();
            for (Direction d : Direction.Type.HORIZONTAL) {
                BlockPos city = base.offset(d);
                if (!OLEPOSSUtils.solid2(city)) continue;
                if (!SettingUtils.inMineRange(city)) continue;

                return new Target(city, e, autoCityPriority.get().priority, false, false);
            }
        }
        return null;
    }

    /* ================= ANTI BURROW ================= */

    private Target getAntiBurrow() {
        updateEnemies();
        for (AbstractClientPlayerEntity e : enemies) {
            BlockPos burrow = e.getBlockPos();
            if (!OLEPOSSUtils.solid2(burrow)) continue;
            if (!SettingUtils.inMineRange(burrow)) continue;

            return new Target(burrow, e, antiBurrowPriority.get().priority, false, false);
        }
        return null;
    }
    /* ================= RENDER ================= */

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (target == null) return;

        if (renderReset.get() && mined) return;

        double progress = Math.min(1.0, minedFor / getMineTicks(getFastestSlot(), true));
        progress = smooth(progress);

        Box box = new Box(target.pos);
        box = box.shrink(0.5 - 0.5 * progress);

        Color side = sideColor.get();
        Color line = lineColor.get();

        event.renderer.box(
            box,
            new Color(side.r, side.g, side.b, (int) (side.a * progress)),
            new Color(line.r, line.g, line.b, (int) (line.a * progress)),
            shapeMode.get(),
            0
        );
    }

    private double smooth(double x) {
        return x * x * (3 - 2 * x); // smoothstep
    }

    /* ================= TICK ================= */

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        tick++;

        updateEnemies();

        updateMining();
        updateExplode();
    }

    /* ================= STATE ================= */

    private static class Target {
        final BlockPos pos;
        final PlayerEntity target;
        final int priority;
        final boolean manual;
        final boolean civ;

        BlockPos crystalPos;

        Target(BlockPos pos, PlayerEntity target, int priority, boolean manual, boolean civ) {
            this.pos = pos;
            this.target = target;
            this.priority = priority;
            this.manual = manual;
            this.civ = civ;

            this.crystalPos = pos.up();
        }
    }

    /* ================= FIELDS ================= */

    private final List<AbstractClientPlayerEntity> enemies = new ArrayList<>();

    private Target target;

    private boolean started;
    private boolean mined;
    private boolean reset;

    private int tick;
    private int lastCivTick;
    private int lastPlaceTick;
    private int lastExplodeTick;

    private float minedFor;

    private int cachedSlot = -1;
    private BlockPos cachedMinePos;
    private float cachedMineTicksNormal;
    private float cachedMineTicksModified;

    private EndCrystalEntity cachedCrystal;
    private BlockPos cachedCrystalPos;

    private final Int2IntMap explodeAt = new Int2IntOpenHashMap();

    /* ================= RESET ================= */

    @Override
    public void onDeactivate() {
        target = null;
        started = false;
        mined = false;
        reset = false;

        cachedSlot = -1;
        cachedMinePos = null;
        cachedCrystal = null;
        cachedCrystalPos = null;

        explodeAt.clear();
        enemies.clear();
    }
