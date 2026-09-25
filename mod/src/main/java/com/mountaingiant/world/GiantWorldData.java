package com.mountaingiant.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Per-world record of the one naturally spawned giant, stored with the overworld.
 * The giant refreshes {@link #lastSeen} while it is loaded; if it has not been seen for a whole day
 * (stuck in unloaded chunks) the slot is considered free again.
 */
public class GiantWorldData extends SavedData {
    private static final String NAME = "mountain_giant";
    /** A giant not seen for this long (ticks) no longer blocks a new one. */
    public static final long FORGET_AFTER = 24000L;

    @Nullable
    private UUID giant;
    private long lastSeen;
    /** Day number whose spawn roll has already been made. */
    private long rolledDay = -1;
    /** Whether tonight's roll said a giant should come. */
    private boolean spawnTonight;

    public static GiantWorldData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(GiantWorldData::new, GiantWorldData::load), NAME);
    }

    private static GiantWorldData load(CompoundTag tag, HolderLookup.Provider registries) {
        GiantWorldData data = new GiantWorldData();
        if (tag.hasUUID("Giant")) {
            data.giant = tag.getUUID("Giant");
        }
        data.lastSeen = tag.getLong("LastSeen");
        data.rolledDay = tag.getLong("RolledDay");
        data.spawnTonight = tag.getBoolean("SpawnTonight");
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (this.giant != null) {
            tag.putUUID("Giant", this.giant);
        }
        tag.putLong("LastSeen", this.lastSeen);
        tag.putLong("RolledDay", this.rolledDay);
        tag.putBoolean("SpawnTonight", this.spawnTonight);
        return tag;
    }

    /** True if some giant is (probably) still out there. */
    public boolean hasGiant(long gameTime) {
        return this.giant != null && gameTime - this.lastSeen < FORGET_AFTER;
    }

    @Nullable
    public UUID getGiant() {
        return this.giant;
    }

    /** Claims the slot for this giant, or refreshes it. Returns false if another giant holds it. */
    public boolean claim(UUID uuid, long gameTime) {
        if (this.giant != null && !this.giant.equals(uuid) && gameTime - this.lastSeen < FORGET_AFTER) {
            return false;
        }
        this.giant = uuid;
        this.lastSeen = gameTime;
        setDirty();
        return true;
    }

    public void release(UUID uuid) {
        if (uuid.equals(this.giant)) {
            this.giant = null;
            setDirty();
        }
    }

    public long getRolledDay() {
        return this.rolledDay;
    }

    public boolean isSpawnTonight() {
        return this.spawnTonight;
    }

    public void setRoll(long day, boolean spawn) {
        this.rolledDay = day;
        this.spawnTonight = spawn;
        setDirty();
    }
}
