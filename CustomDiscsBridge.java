package ru.discplayer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Talks to the CustomDiscs plugin (https://modrinth.com/plugin/customdiscs) through reflection,
 * so DiscPlayer builds and runs without CustomDiscs and without compile-time access to it.
 *
 * Used pieces (all public in CustomDiscs 2.7.0):
 *  - CustomDiscsAPI.get().isCustomDisc(ItemStack) / .getLavaPlayerManager()
 *  - LavaPlayerManager.isPlaying(Block) / stopPlaying(Block) / getAudioChannel(Block)
 *  - LegacyUtil.getDiscEntry(ItemStack) + PlayUtil.play(Block, DiscEntry, Player): exactly what CustomDiscs
 *    itself runs when a player puts a disc into a jukebox (starts the Simple Voice Chat stream at that block)
 */
final class CustomDiscsBridge {

    private final Logger log;
    private final ClassLoader cl;
    private final Object api;
    private final Object lava;
    private final Method mIsCustom, mGetEntry, mEntryName, mPlay, mIsPlaying, mStop, mGetChannel;

    // used only to move the sound of a portable player together with its owner
    private Object voiceApi;
    private Method mCreatePosition, mUpdateLocation;
    private boolean moveBroken;

    private CustomDiscsBridge(Plugin owner, Plugin cd) throws Exception {
        this.log = owner.getLogger();
        this.cl = cd.getClass().getClassLoader();

        Class<?> apiCls = cl.loadClass("space.subkek.customdiscs.api.CustomDiscsAPI");
        this.api = apiCls.getMethod("get").invoke(null);
        this.mIsCustom = apiCls.getMethod("isCustomDisc", ItemStack.class);
        this.lava = apiCls.getMethod("getLavaPlayerManager").invoke(api);

        Class<?> lavaCls = cl.loadClass("space.subkek.customdiscs.api.LavaPlayerManager");
        this.mIsPlaying = lavaCls.getMethod("isPlaying", Block.class);
        this.mStop = lavaCls.getMethod("stopPlaying", Block.class);
        this.mGetChannel = lavaCls.getMethod("getAudioChannel", Block.class);

        Class<?> entryCls = cl.loadClass("space.subkek.customdiscs.api.DiscEntry");
        this.mEntryName = entryCls.getMethod("getName");
        this.mGetEntry = cl.loadClass("space.subkek.customdiscs.util.LegacyUtil")
                .getMethod("getDiscEntry", ItemStack.class);
        this.mPlay = cl.loadClass("space.subkek.customdiscs.util.PlayUtil")
                .getMethod("play", Block.class, entryCls, Player.class);
    }

    /** null when CustomDiscs is missing, disabled, or its API looks different from the expected one */
    static CustomDiscsBridge tryCreate(Plugin owner) {
        Plugin cd = Bukkit.getPluginManager().getPlugin("CustomDiscs");
        if (cd == null || !cd.isEnabled()) return null;
        try {
            return new CustomDiscsBridge(owner, cd);
        } catch (Throwable t) {
            owner.getLogger().warning("CustomDiscs found, but its API could not be used (custom discs disabled): " + t);
            return null;
        }
    }

    boolean isCustom(ItemStack it) {
        try {
            return it != null && it.getType().isRecord() && (boolean) mIsCustom.invoke(api, it);
        } catch (Throwable t) {
            return false;
        }
    }

    /** display name of the song, or null */
    String name(ItemStack it) {
        try {
            Object entry = mGetEntry.invoke(null, it);
            Object c = mEntryName.invoke(entry);
            return c instanceof Component comp ? PlainTextComponentSerializer.plainText().serialize(comp) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** starts the disc at {@code block}; the same call CustomDiscs makes when a disc is inserted */
    boolean play(Block block, ItemStack disc) {
        try {
            Object entry = mGetEntry.invoke(null, disc);
            mPlay.invoke(null, block, entry, null);
            return true;
        } catch (Throwable t) {
            log.warning("CustomDiscs play failed: " + t);
            return false;
        }
    }

    boolean isPlaying(Block block) {
        try {
            return (boolean) mIsPlaying.invoke(lava, block);
        } catch (Throwable t) {
            return false;
        }
    }

    void stop(Block block) {
        try {
            if (isPlaying(block)) mStop.invoke(lava, block);
        } catch (Throwable t) {
            log.warning("CustomDiscs stop failed: " + t);
        }
    }

    /** moves the voice-chat audio channel that plays at {@code block} to {@code to} (best effort) */
    void moveChannel(Block block, Location to) {
        if (moveBroken) return;
        try {
            Object channel = mGetChannel.invoke(lava, block);
            if (channel == null) return;
            if (voiceApi == null && !initVoice()) { moveBroken = true; return; }
            Object pos = mCreatePosition.invoke(voiceApi, to.getX(), to.getY(), to.getZ());
            mUpdateLocation.invoke(channel, pos);
        } catch (Throwable t) {
            moveBroken = true;
            log.warning("Portable custom discs will play from a fixed spot (can't move the voice channel): " + t);
        }
    }

    private boolean initVoice() throws Exception {
        Class<?> addon = cl.loadClass("space.subkek.customdiscs.CDVoiceAddon");
        Object inst = addon.getMethod("getInstance").invoke(null);
        Object vapi = addon.getMethod("getVoicechatApi").invoke(inst);
        if (vapi == null) return false;

        Class<?> srv = cl.loadClass("de.maxhenkel.voicechat.api.VoicechatServerApi");
        Method create = srv.getMethod("createPosition", double.class, double.class, double.class);

        Class<?> loc = cl.loadClass("de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel");
        Method update = null;
        for (Method m : loc.getMethods())
            if (m.getName().equals("updateLocation") && m.getParameterCount() == 1) { update = m; break; }
        if (update == null) return false;

        this.voiceApi = vapi;
        this.mCreatePosition = create;
        this.mUpdateLocation = update;
        return true;
    }
}
