package me.alpha432.oyvey.features.modules.combat;

import me.alpha432.oyvey.features.modules.Module;
import me.alpha432.oyvey.features.setting.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.CPacketAnimation;
import net.minecraft.network.play.client.CPacketUseEntity;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.RayTraceResult;

import java.util.Random;

/**
 * Triggerbot tuned for 1.9+ PvP:
 * - Uses getCooledAttackStrength to respect 1.9+ weapon cooldown mechanics (prevents 1.8-style spam)
 * - Smart mode: attacks immediately when cooled enough (very responsive)
 * - Fallback CPS mode: limits attacks by CPS when Smart is disabled
 * - Optionally waits for a perfect critical hit window when you jump (CritOnJump)
 * - Optionally enforces a configurable minimum-to-maximum cooldown (ms) between attacks
 * - Settings: Range, Smart, MinCooled, CPS, OnlyPlayers, Packet, FOV, RayTrace, RandomDelay, Silent, CritOnJump,
 *   UseCDRange, MinCooldown, MaxCooldown
 *
 * Note: If your client uses different mappings for getCooledAttackStrength or player controller methods,
 * adapt those method names accordingly.
 */
public class Triggerbot extends Module {
    private final Minecraft mc = Minecraft.getMinecraft();
    private final Random random = new Random();

    public Setting<Float> range = register(new Setting<>("Range", 4.5f, 0.1f, 10.0f));
    public Setting<Boolean> smart = register(new Setting<>("Smart", true)); // Use 1.9+ cooldown
    public Setting<Float> minCooled = register(new Setting<>("MinCooled", 0.95f, 0.0f, 1.0f)); // threshold for cooled attack strength
    public Setting<Integer> cps = register(new Setting<>("CPS", 12, 1, 20)); // used when Smart is false
    public Setting<Boolean> onlyPlayers = register(new Setting<>("OnlyPlayers", true));
    public Setting<Boolean> allowMobs = register(new Setting<>("AllowMobs", false));
    public Setting<Boolean> packet = register(new Setting<>("Packet", false));
    public Setting<Float> fov = register(new Setting<>("FOV", 180.0f, 0.0f, 180.0f));
    public Setting<Boolean> raytrace = register(new Setting<>("RayTrace", true));
    public Setting<Boolean> randomDelay = register(new Setting<>("RandomDelay", true));
    public Setting<Integer> jitterMs = register(new Setting<>("JitterMs", 20, 0, 150)); // random extra delay ms to avoid perfect timing
    public Setting<Boolean> silent = register(new Setting<>("Silent", false));

    // New option: when true, if you are in the air (jumping/falling) Triggerbot will wait until a proper critical hit window
    public Setting<Boolean> critOnJump = register(new Setting<>("CritOnJump", false));

    // New cooldown-range options (milliseconds). When UseCDRange is true, Triggerbot will enforce a random cooldown
    // between MinCooldown and MaxCooldown (inclusive) before allowing the next attack.
    public Setting<Boolean> useCdRange = register(new Setting<>("UseCDRange", false));
    public Setting<Integer> minCooldown = register(new Setting<>("MinCooldown", 50, 0, 5000)); // ms
    public Setting<Integer> maxCooldown = register(new Setting<>("MaxCooldown", 200, 0, 5000)); // ms

    private long lastAttackTime = 0L;

    public Triggerbot() {
        super("Triggerbot", "Automatically attacks entities you aim at (1.9+ friendly)", Category.COMBAT, true, false, false);
    }

    @Override
    public void onUpdate() {
        if (mc.player == null || mc.world == null) return;

        RayTraceResult ray = mc.objectMouseOver;
        if (ray == null || ray.typeOfHit != RayTraceResult.Type.ENTITY) return;

        Entity target = ray.entityHit;
        if (target == null) return;
        if (!isValidTarget(target)) return;

        double distance = mc.player.getDistance(target);
        if (distance > range.getValue()) return;

        if (!isInFov(target, fov.getValue())) return;

        // If crit-on-jump option is enabled, and the player is in the air (jump/fall),
        // only attack when a critical is possible (so we wait for the fall window)
        if (critOnJump.getValue()) {
            if (!mc.player.onGround) {
                if (!isCriticalPossible()) {
                    return;
                }
            }
        }

        long now = System.currentTimeMillis();

        // Determine extra delays:
        // - cdRand: random cooldown between minCooldown and maxCooldown if useCdRange is enabled
        // - jitter: small random jitter to make timing less deterministic
        long cdRand = 0L;
        if (useCdRange.getValue()) {
            int min = Math.max(0, minCooldown.getValue());
            int max = Math.max(min, maxCooldown.getValue()); // ensure max >= min
            cdRand = min;
            if (max > min) cdRand = min + random.nextInt(max - min + 1);
        }

        long jitter = 0L;
        if (randomDelay.getValue()) {
            jitter = random.nextInt(Math.max(1, jitterMs.getValue()) + 1);
        }

        long requiredDelay = cdRand + jitter;

        if (smart.getValue()) {
            // Use 1.9+ cooldown check: attack when the player has enough cooled attack strength
            float strength = mc.player.getCooledAttackStrength(0.5F);
            if (strength < minCooled.getValue()) return;

            // Respect cooldown-range / jitter
            if (now - lastAttackTime < requiredDelay) return;

            doAttack(target);
            lastAttackTime = now;
        } else {
            // Legacy behavior using CPS limiting (keeps rate under CPS) but still can respect cooldown-range if enabled
            long delayByCps = Math.round(1000.0 / Math.max(1, cps.getValue()));
            long effectiveDelay = delayByCps;
            if (useCdRange.getValue()) {
                // If the cooldown range is enabled, prefer the larger of CPS delay and configured random cooldown.
                effectiveDelay = Math.max(delayByCps, cdRand);
            }
            // Add jitter on top
            effectiveDelay += jitter;

            if (now - lastAttackTime < effectiveDelay) return;

            doAttack(target);
            lastAttackTime = now;
        }
    }

    private boolean isValidTarget(Entity e) {
        if (e == mc.player) return false;
        if (!raytrace.getValue() && !(e instanceof EntityPlayer)) {
            // if raytrace disabled, prefer players unless allowMobs is true
            return allowMobs.getValue() && !(e == mc.player);
        }

        if (onlyPlayers.getValue() && !(e instanceof EntityPlayer)) return false;
        // Optional: add friend, team, invisibility checks if your client provides them
        return true;
    }

    /**
     * Checks whether a critical hit can currently occur.
     * Typical critical conditions (vanilla):
     *  - player is not on ground
     *  - fallDistance > small threshold
     *  - not in water, not in lava, not on ladder, not elytra flying
     */
    private boolean isCriticalPossible() {
        if (mc.player == null) return false;
        if (mc.player.onGround) return false;
        if (mc.player.isInWater() || mc.player.isInLava()) return false;
        if (mc.player.isOnLadder()) return false;
        // Elytra flying check (1.9+)
        try {
            if (mc.player.isElytraFlying()) return false;
        } catch (Throwable ignored) {
            // some mappings/versions may not have isElytraFlying; ignore if absent
        }
        // Require a small fall distance to ensure critical (tweak threshold if needed)
        return mc.player.fallDistance > 0.1F;
    }

    private boolean isInFov(Entity e, float maxFov) {
        double dx = e.posX - mc.player.posX;
        double dz = e.posZ - mc.player.posZ;
        double yawToEntity = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
        float diff = Math.abs(wrapAngleTo180_float((float)(yawToEntity - mc.player.rotationYaw)));
        return diff <= maxFov / 2.0f;
    }

    private float wrapAngleTo180_float(float value) {
        value %= 360.0F;
        if (value >= 180.0F) {
            value -= 360.0F;
        }
        if (value < -180.0F) {
            value += 360.0F;
        }
        return value;
    }

    private void doAttack(Entity target) {
        try {
            if (packet.getValue()) {
                // Send use entity packet (attack) and animation packet.
                // Note: sending raw packets may behave differently server-side; still respect cooldown by checking getCooledAttackStrength above.
                mc.player.connection.sendPacket(new CPacketUseEntity(target));
                mc.player.connection.sendPacket(new CPacketAnimation(EnumHand.MAIN_HAND));
            } else {
                // Normal client-side attack which should play well with 1.9+ cooldown mechanics.
                mc.playerController.attackEntity(mc.player, target);
                if (!silent.getValue()) mc.player.swingArm(EnumHand.MAIN_HAND);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    @Override
    public void onEnable() {
        lastAttackTime = 0L;
    }

    @Override
    public void onDisable() {
        // nothing special
    }
}
