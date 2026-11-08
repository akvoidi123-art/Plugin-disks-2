package ru.discplayer;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Portable disc player (item) and 5-slot queue for the vanilla jukebox.
 * Queue order = slot order. The first disc plays, after it ends it goes to the END of the queue.
 * Discs from the CustomDiscs plugin work too (queue stores the whole item; playback goes through CustomDiscs).
 */
public final class DiscPlayerPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final int SLOTS = 5;                 // disc slots (top row, left)
    private static final int GUI_SIZE = 18;             // 2 chest rows: discs on top, buttons below
    private static final int START_SLOT = 11, STATUS_SLOT = 13, STOP_SLOT = 15;
    private static final String DISC_PREFIX = "MUSIC_DISC_";
    private static final String DEFAULT_NAME = "<b><#2E3192>D<#2C489E>i<#2A5FAA>s<#2876B6>c<#268DC2>P<#23A3CF>l<#21BADB>a<#1FD1E7>y<#1DE8F3>e<#1BFFFF>r</b>";
    /** a custom disc that stops sooner than this after its start is treated as 'could not start' */
    private static final long CUSTOM_FAIL_MS = 2000;

    /** Fallback lengths (seconds), used only if the server can't tell the song length. */
    private static final Map<String, Integer> FALLBACK = Map.ofEntries(
            Map.entry("13", 178), Map.entry("cat", 185), Map.entry("blocks", 345), Map.entry("chirp", 185),
            Map.entry("far", 174), Map.entry("mall", 197), Map.entry("mellohi", 96), Map.entry("stal", 150),
            Map.entry("strad", 188), Map.entry("ward", 251), Map.entry("11", 71), Map.entry("wait", 238),
            Map.entry("otherside", 195), Map.entry("5", 178), Map.entry("pigstep", 149), Map.entry("relic", 218),
            Map.entry("creator", 176), Map.entry("creator_music_box", 73), Map.entry("precipice", 299));

    private NamespacedKey keyPaused;    // portable item / jukebox: music is switched off
    private NamespacedKey keyMarker;    // portable item: marks it as ours (id is assigned lazily)
    private NamespacedKey recipeKey;
    private NamespacedKey keyId;        // portable item: its unique id
    private NamespacedKey keyDiscs;     // portable item: queue (material names, ';')
    private NamespacedKey keyQueue;     // jukebox: discs AFTER the one in the jukebox
    private NamespacedKey keyManaged;   // jukebox: managed by this plugin
    private CustomDiscsBridge bridge;   // null when CustomDiscs is not installed

    /** Inventory holder for our hopper-style menu. */
    static final class Gui implements InventoryHolder {
        UUID itemId;            // portable
        String blockKey;        // jukebox
        List<ItemStack> before = new ArrayList<>();
        boolean paused;
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    /** One running portable player. */
    private static final class Session {
        UUID holder;
        long startMs, lengthMs;
        Key key;
        Sound sound;
        boolean custom;         // played by CustomDiscs (voice chat) instead of a vanilla sound
        Block block;            // custom: the block CustomDiscs plays at
        final Set<UUID> listeners = new HashSet<>();
    }

    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Set<UUID> openItemGuis = new HashSet<>();
    private final Set<String> openBlockGuis = new HashSet<>();
    private final Set<String> jukeboxes = new LinkedHashSet<>();
    private final Map<String, Long> customStarts = new HashMap<>();   // jukebox key -> when we started a custom disc
    private boolean dirty;

    // ------------------------------------------------------------------ life cycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        keyPaused = new NamespacedKey(this, "paused");
        keyMarker = new NamespacedKey(this, "portable");
        recipeKey = new NamespacedKey(this, "portable_player");
        keyId = new NamespacedKey(this, "player_id");
        keyDiscs = new NamespacedKey(this, "discs");
        keyQueue = new NamespacedKey(this, "queue");
        keyManaged = new NamespacedKey(this, "managed");

        bridge = CustomDiscsBridge.tryCreate(this);
        getLogger().info(bridge != null ? "CustomDiscs found: custom discs are supported."
                : "CustomDiscs not found: only vanilla discs are supported.");

        loadJukeboxes();
        registerRecipe();
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("discplayer");
        if (cmd != null) { cmd.setExecutor(this); cmd.setTabCompleter(this); }

        getServer().getScheduler().runTaskTimer(this, () -> {
            try { tickPortables(); } catch (Exception ex) { getLogger().warning("portable tick: " + ex); }
            try { tickJukeboxes(); } catch (Exception ex) { getLogger().warning("jukebox tick: " + ex); }
            if (dirty) saveJukeboxes();
        }, 20L, 20L);

        // the voice-chat sound of a portable player follows its owner
        getServer().getScheduler().runTaskTimer(this, this::followCustom, 4L, 4L);
    }

    @Override
    public void onDisable() {
        for (Player p : getServer().getOnlinePlayers())
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Gui) p.closeInventory();
        for (UUID id : new ArrayList<>(sessions.keySet())) stopSession(id);
        saveJukeboxes();
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent e) {
        if (bridge == null && e.getPlugin().getName().equals("CustomDiscs")) {
            bridge = CustomDiscsBridge.tryCreate(this);
            if (bridge != null) getLogger().info("CustomDiscs enabled: custom discs are supported.");
        }
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent e) {
        if (e.getPlugin().getName().equals("CustomDiscs")) {
            for (UUID id : new ArrayList<>(sessions.keySet())) if (sessions.get(id).custom) sessions.remove(id);
            bridge = null;
        }
    }

    // ------------------------------------------------------------------ registry of managed jukeboxes

    private File jukeboxFile() { return new File(getDataFolder(), "jukeboxes.yml"); }

    private void loadJukeboxes() {
        jukeboxes.addAll(YamlConfiguration.loadConfiguration(jukeboxFile()).getStringList("list"));
    }

    private void saveJukeboxes() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("list", new ArrayList<>(jukeboxes));
        try { y.save(jukeboxFile()); dirty = false; } catch (IOException ex) { getLogger().warning("save: " + ex); }
    }

    private static String blockKey(Block b) { return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ(); }

    private Block blockOf(String key) {
        String[] p = key.split(";");
        if (p.length != 4) return null;
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        try { return w.getBlockAt(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])); }
        catch (NumberFormatException ex) { return null; }
    }

    // ------------------------------------------------------------------ disc helpers

    private boolean isCustom(ItemStack it) { return bridge != null && bridge.isCustom(it); }

    /**
     * Plain vanilla discs and CustomDiscs discs may go into the menus. Any other item with data (renamed disc,
     * other plugins' discs) is rejected: the queue could not restore it faithfully.
     */
    private boolean isDisc(ItemStack it) {
        return it != null && it.getType().isRecord() && (!it.hasItemMeta() || isCustom(it));
    }

    private static ItemStack one(ItemStack it) {
        ItemStack c = it.clone();
        c.setAmount(1);
        return c;
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return a == null || b == null ? a == b : a.isSimilar(b);
    }

    private static String songName(Material m) {
        return m.name().substring(DISC_PREFIX.length()).toLowerCase(Locale.ROOT);
    }

    /** plain disc: its material name (compact, also the old save format); custom disc: the whole item as base64 */
    private static String encode(ItemStack it) {
        if (!it.hasItemMeta()) return it.getType().name();
        return Base64.getEncoder().encodeToString(one(it).serializeAsBytes());
    }

    private static ItemStack decode(String s) {
        s = s.trim();
        if (s.isEmpty()) return null;
        if (s.length() < 48) {
            Material m = Material.matchMaterial(s);
            if (m != null) return m.isRecord() ? new ItemStack(m) : null;
        }
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(s));
        } catch (Exception ex) {
            return null;
        }
    }

    private List<ItemStack> readDiscs(PersistentDataContainer pdc, NamespacedKey key) {
        List<ItemStack> out = new ArrayList<>();
        String s = pdc.get(key, PersistentDataType.STRING);
        if (s == null || s.isBlank()) return out;
        for (String part : s.split(";")) {
            ItemStack it = decode(part);
            if (it != null && it.getType().isRecord()) out.add(it);
        }
        return out;
    }

    private void writeDiscs(PersistentDataContainer pdc, NamespacedKey key, List<ItemStack> list) {
        if (list.isEmpty()) pdc.remove(key);
        else pdc.set(key, PersistentDataType.STRING, list.stream().map(DiscPlayerPlugin::encode).collect(Collectors.joining(";")));
    }

    private long lengthMs(Material disc) {
        String name = songName(disc);
        try {
            Object reg = Registry.class.getField("JUKEBOX_SONG").get(null);
            Object song = ((Registry<?>) reg).get(NamespacedKey.minecraft(name));
            if (song != null) {
                Method m = Class.forName("org.bukkit.JukeboxSong").getMethod("getLengthInSeconds");
                Object v = m.invoke(song);
                if (v instanceof Number n && n.doubleValue() > 0) return (long) (n.doubleValue() * 1000);
            }
        } catch (Throwable ignored) { }
        return FALLBACK.getOrDefault(name, 180) * 1000L;
    }

    private static String pretty(Material m) { return songName(m).replace('_', ' '); }

    /** name shown in lists: the song title for custom discs, the vanilla disc name otherwise */
    private String discName(ItemStack it) {
        if (it.hasItemMeta() && bridge != null) {
            String n = bridge.name(it);
            if (n != null && !n.isBlank()) return n;
        }
        return pretty(it.getType());
    }

    // ------------------------------------------------------------------ portable item

    private static Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private void applyLore(ItemMeta meta, List<ItemStack> list) {
        List<Component> lore = new ArrayList<>();
        lore.add(plain("ПКМ или Шифт+ПКМ по блоку - открыть меню", NamedTextColor.GRAY));
        boolean paused = meta.getPersistentDataContainer().has(keyPaused, PersistentDataType.BYTE);
        lore.add(paused ? plain("Остановлено", NamedTextColor.RED) : plain("Играет", NamedTextColor.GREEN));
        for (int i = 0; i < list.size(); i++)
            lore.add(plain((i == 0 ? "> " : "  ") + (i + 1) + ". " + discName(list.get(i)),
                    i == 0 ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY));
        meta.lore(lore);
    }

    public ItemStack createPortable() {
        ItemStack it = new ItemStack(Material.JUKEBOX);
        ItemMeta m = it.getItemMeta();
        m.displayName(MiniMessage.miniMessage()
                .deserialize(getConfig().getString("portable.item-name-mini", DEFAULT_NAME))
                .decoration(TextDecoration.ITALIC, false));
        m.setEnchantmentGlintOverride(true);
        m.setMaxStackSize(1);                       // every player has its own queue
        m.getPersistentDataContainer().set(keyMarker, PersistentDataType.BYTE, (byte) 1);
        m.getPersistentDataContainer().set(keyPaused, PersistentDataType.BYTE, (byte) 1);   // starts switched off
        applyLore(m, List.of());
        it.setItemMeta(m);
        return it;
    }

    private boolean isPortable(ItemStack it) {
        if (it == null || it.getType() != Material.JUKEBOX || !it.hasItemMeta()) return false;
        PersistentDataContainer pdc = it.getItemMeta().getPersistentDataContainer();
        return pdc.has(keyMarker, PersistentDataType.BYTE) || pdc.has(keyId, PersistentDataType.STRING);
    }

    /** crafted items get their unique id the first time we see them */
    private ItemStack withId(ItemStack it) {
        ItemStack c = it.clone();
        ItemMeta m = c.getItemMeta();
        m.getPersistentDataContainer().set(keyId, PersistentDataType.STRING, UUID.randomUUID().toString());
        c.setItemMeta(m);
        return c;
    }

    // ------------------------------------------------------------------ crafting

    private void registerRecipe() {
        Bukkit.removeRecipe(recipeKey);
        if (!getConfig().getBoolean("recipe.enabled", true)) return;
        ShapedRecipe r = new ShapedRecipe(recipeKey, createPortable());
        r.shape("PIP", "IDI", "PIP");
        r.setIngredient('P', new RecipeChoice.MaterialChoice(Tag.PLANKS));
        r.setIngredient('I', Material.IRON_INGOT);
        r.setIngredient('D', Material.DIAMOND);
        Bukkit.addRecipe(r);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (getConfig().getBoolean("recipe.enabled", true)) e.getPlayer().discoverRecipe(recipeKey);
    }

    private UUID idOf(ItemStack it) {
        try {
            String s = it.getItemMeta().getPersistentDataContainer().get(keyId, PersistentDataType.STRING);
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException ex) { return null; }
    }

    private List<ItemStack> readItem(ItemStack it) {
        return readDiscs(it.getItemMeta().getPersistentDataContainer(), keyDiscs);
    }

    private void writeItem(ItemStack it, List<ItemStack> list) {
        ItemMeta m = it.getItemMeta();
        writeDiscs(m.getPersistentDataContainer(), keyDiscs, list);
        applyLore(m, list);
        it.setItemMeta(m);
    }

    /** index of the portable item with this id in the player's inventory, or -1 */
    private int findPortable(Player p, UUID id) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (isPortable(it) && id.equals(idOf(it))) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ portable playback

    private void tickPortables() {
        long now = System.currentTimeMillis();
        long gap = getConfig().getLong("portable.gap-ms", 1500);
        Set<UUID> seen = new HashSet<>();

        for (Player p : getServer().getOnlinePlayers()) {
            PlayerInventory inv = p.getInventory();
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack it = inv.getItem(i);
                if (!isPortable(it)) continue;
                UUID id = idOf(it);
                if (id == null) { it = withId(it); inv.setItem(i, it); id = idOf(it); }
                if (id == null || !seen.add(id)) continue;
                if (openItemGuis.contains(id)) continue;           // menu is open: leave it alone

                List<ItemStack> list = readItem(it);
                Session s = sessions.get(id);
                if (list.isEmpty() || isPaused(it)) { if (s != null) stopSession(id); continue; }
                if (s != null && !s.holder.equals(p.getUniqueId())) { stopSession(id); s = null; }
                // custom sound is tied to a block of one world: restart if the owner changed world or CustomDiscs is gone
                if (s != null && s.custom && (bridge == null || !s.block.getWorld().equals(p.getWorld()))) {
                    stopSession(id);
                    s = null;
                }

                if (s != null) {
                    boolean finished;
                    if (s.custom) {
                        finished = !bridge.isPlaying(s.block);
                        if (finished && now - s.startMs < CUSTOM_FAIL_MS) {     // it never started: switch the player off
                            failPortable(p, id, it, inv, i);
                            continue;
                        }
                    } else {
                        finished = now - s.startMs >= s.lengthMs + gap;
                    }
                    if (finished) {                                  // disc finished -> goes to the end
                        stopSession(id);
                        list.add(list.remove(0));
                        writeItem(it, list);
                        inv.setItem(i, it);
                        s = null;
                    }
                }
                if (s == null) s = startSession(id, p, list.get(0));
                if (s == null) continue;                             // custom disc but CustomDiscs is unavailable
                if (!s.custom) updateListeners(s, p);
            }
        }
        for (UUID id : new ArrayList<>(sessions.keySet())) if (!seen.contains(id)) stopSession(id);
    }

    private Session startSession(UUID id, Player holder, ItemStack disc) {
        Session s = new Session();
        s.holder = holder.getUniqueId();
        s.startMs = System.currentTimeMillis();
        if (disc.hasItemMeta()) {                              // a CustomDiscs disc
            if (bridge == null || !bridge.isCustom(disc)) return null;
            s.custom = true;
            s.block = holder.getLocation().getBlock();         // CustomDiscs keys its stream by block; we move it with the owner
            bridge.play(s.block, disc);
            sessions.put(id, s);
            return s;
        }
        Material m = disc.getType();
        s.lengthMs = lengthMs(m);
        s.key = Key.key("minecraft", "music_disc." + songName(m));
        s.sound = Sound.sound(s.key, Sound.Source.RECORD, (float) getConfig().getDouble("portable.volume", 2.0), 1.0f);
        sessions.put(id, s);
        return s;
    }

    private void stopSession(UUID id) {
        Session s = sessions.remove(id);
        if (s == null) return;
        if (s.custom) {
            if (bridge != null) bridge.stop(s.block);
            return;
        }
        for (UUID u : s.listeners) {
            Player q = Bukkit.getPlayer(u);
            if (q != null) q.stopSound(SoundStop.named(s.key));
        }
    }

    /** a custom disc could not be started (voice chat missing, bad file...): switch this player off instead of looping */
    private void failPortable(Player p, UUID id, ItemStack it, PlayerInventory inv, int slot) {
        stopSession(id);
        ItemMeta m = it.getItemMeta();
        m.getPersistentDataContainer().set(keyPaused, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        writeItem(it, readItem(it));
        inv.setItem(slot, it);
        p.sendMessage(Component.text("Пластинка не запустилась (проверь CustomDiscs и Simple Voice Chat). Проигрыватель выключен.",
                NamedTextColor.RED));
    }

    private void followCustom() {
        if (bridge == null) return;
        for (Session s : sessions.values()) {
            if (!s.custom) continue;
            Player o = Bukkit.getPlayer(s.holder);
            if (o != null && o.getWorld().equals(s.block.getWorld())) bridge.moveChannel(s.block, o.getLocation().add(0, 1.0, 0));
        }
    }

    /** Everyone near the owner hears the disc; the sound follows the owner. */
    private void updateListeners(Session s, Player owner) {
        double radius = getConfig().getDouble("portable.listen-radius", 32.0);
        double near2 = radius * radius, far2 = (radius + 8) * (radius + 8);
        Location ol = owner.getLocation();

        Iterator<UUID> it = s.listeners.iterator();
        while (it.hasNext()) {
            Player q = Bukkit.getPlayer(it.next());
            if (q == null) { it.remove(); continue; }
            if (!q.getWorld().equals(ol.getWorld()) || q.getLocation().distanceSquared(ol) > far2) {
                q.stopSound(SoundStop.named(s.key));
                it.remove();
            }
        }
        for (Player q : owner.getWorld().getPlayers()) {
            if (s.listeners.contains(q.getUniqueId())) continue;
            if (q.getLocation().distanceSquared(ol) <= near2) {
                q.playSound(s.sound, owner);        // owner is the emitter, so the sound follows them
                s.listeners.add(q.getUniqueId());
            }
        }
    }

    // ------------------------------------------------------------------ menus

    private boolean isPaused(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(keyPaused, PersistentDataType.BYTE);
    }

    private ItemStack uiItem(Material m, String name, NamedTextColor color, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(plain(name, color));
        if (lore.length > 0) meta.lore(Arrays.stream(lore).map(l -> plain(l, NamedTextColor.GRAY)).toList());
        it.setItemMeta(meta);
        return it;
    }

    private void refreshButtons(Gui g) {
        g.inv.setItem(START_SLOT, uiItem(Material.LIME_DYE, "▶ Запуск", NamedTextColor.GREEN, "Включить музыку"));
        g.inv.setItem(STOP_SLOT, uiItem(Material.RED_DYE, "■ Стоп", NamedTextColor.RED, "Выключить музыку"));
        g.inv.setItem(STATUS_SLOT, g.paused
                ? uiItem(Material.NOTE_BLOCK, "Остановлено", NamedTextColor.RED)
                : uiItem(Material.NOTE_BLOCK, "Играет", NamedTextColor.GREEN));
    }

    private Gui newGui(String title, List<ItemStack> list, boolean paused) {
        Gui g = new Gui();
        g.before = new ArrayList<>(list);
        g.paused = paused;
        g.inv = Bukkit.createInventory(g, GUI_SIZE, Component.text(title));
        for (int i = SLOTS; i < GUI_SIZE; i++)
            g.inv.setItem(i, uiItem(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY));
        for (int i = 0; i < list.size() && i < SLOTS; i++) g.inv.setItem(i, list.get(i).clone());
        refreshButtons(g);
        return g;
    }

    private List<ItemStack> collectDiscs(Inventory inv) {
        List<ItemStack> l = new ArrayList<>();
        for (int i = 0; i < SLOTS; i++) {
            ItemStack it = inv.getItem(i);
            if (isDisc(it)) l.add(one(it));
        }
        return l;
    }

    private void openPortable(Player p, ItemStack item) {
        UUID id = idOf(item);
        if (id == null) {
            item = withId(item);
            p.getInventory().setItemInMainHand(item);
            id = idOf(item);
        }
        if (id == null || openItemGuis.contains(id)) return;
        Gui g = newGui(getConfig().getString("portable.gui-title", "Портативный проигрыватель"), readItem(item), isPaused(item));
        g.itemId = id;
        openItemGuis.add(id);
        p.openInventory(g.inv);
    }

    /** is anything playing in this jukebox: a vanilla song or a CustomDiscs stream */
    private boolean isActive(Block b, Jukebox j) {
        return j.isPlaying() || (bridge != null && bridge.isPlaying(b));
    }

    private void openJukebox(Player p, Block b) {
        String key = blockKey(b);
        if (openBlockGuis.contains(key)) return;
        Jukebox j = (Jukebox) b.getState();
        ItemStack rec = j.hasRecord() ? j.getRecord() : null;
        if (rec != null && rec.hasItemMeta() && !isCustom(rec)) {
            p.sendMessage(Component.text("В проигрывателе особая пластинка. Достань её обычным ПКМ и открой меню снова.", NamedTextColor.RED));
            return;
        }
        PersistentDataContainer pdc = j.getPersistentDataContainer();
        List<ItemStack> list = new ArrayList<>();
        if (rec != null && rec.getType().isRecord()) list.add(one(rec));
        list.addAll(readDiscs(pdc, keyQueue));
        boolean paused = pdc.has(keyPaused, PersistentDataType.BYTE)
                || (!pdc.has(keyManaged, PersistentDataType.BYTE) && !isActive(b, j));
        Gui g = newGui(getConfig().getString("jukebox.gui-title", "Проигрыватель"), list, paused);
        g.blockKey = key;
        openBlockGuis.add(key);
        p.openInventory(g.inv);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        Player p = e.getPlayer();

        if (a == Action.RIGHT_CLICK_BLOCK) {
            Block b = e.getClickedBlock();
            if (b != null && b.getType() == Material.JUKEBOX && p.isSneaking()) {
                if (e.useInteractedBlock() == Event.Result.DENY) return;      // protected area
                e.setCancelled(true);
                openJukebox(p, b);
                return;
            }
        }
        if (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) {
            ItemStack it = e.getItem();
            if (isPortable(it)) {
                e.setCancelled(true);
                openPortable(p, it);
            }
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (isPortable(e.getItemInHand())) e.setCancelled(true);       // it is a player, not a block
    }

    // ------------------------------------------------------------------ menu rules

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Gui g)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int raw = e.getRawSlot();
        boolean inTop = raw >= 0 && raw < top.getSize();
        ClickType c = e.getClick();

        if (inTop && raw >= SLOTS) {                       // buttons and decoration are never movable
            e.setCancelled(true);
            if (raw == START_SLOT) pressButton(g, p, true);
            else if (raw == STOP_SLOT) pressButton(g, p, false);
            return;
        }
        if (c == ClickType.DOUBLE_CLICK) { e.setCancelled(true); return; }
        if (e.isShiftClick() && !inTop && e.getCurrentItem() != null && !isDisc(e.getCurrentItem())) {
            e.setCancelled(true); return;
        }
        if (inTop) {
            ItemStack cursor = e.getCursor();
            if (cursor != null && !cursor.getType().isAir() && !isDisc(cursor)) { e.setCancelled(true); return; }
            if (c == ClickType.NUMBER_KEY) {
                ItemStack hb = p.getInventory().getItem(e.getHotbarButton());
                if (hb != null && !hb.getType().isAir() && !isDisc(hb)) e.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Gui)) return;
        boolean touchesUi = e.getRawSlots().stream().anyMatch(s -> s >= SLOTS && s < top.getSize());
        boolean touchesTop = e.getRawSlots().stream().anyMatch(s -> s < top.getSize());
        if (touchesUi || (touchesTop && !isDisc(e.getOldCursor()))) e.setCancelled(true);
    }

    /** Start / Stop button */
    private void pressButton(Gui g, Player p, boolean start) {
        List<ItemStack> list = collectDiscs(g.inv);
        if (start && list.isEmpty()) {
            p.sendActionBar(Component.text("Сначала положи пластинки", NamedTextColor.RED));
            return;
        }
        g.paused = !start;
        if (g.itemId != null) {
            int idx = findPortable(p, g.itemId);
            if (idx >= 0) {
                ItemStack it = p.getInventory().getItem(idx);
                ItemMeta m = it.getItemMeta();
                if (g.paused) m.getPersistentDataContainer().set(keyPaused, PersistentDataType.BYTE, (byte) 1);
                else m.getPersistentDataContainer().remove(keyPaused);
                it.setItemMeta(m);
                writeItem(it, list);
                p.getInventory().setItem(idx, it);
            }
            stopSession(g.itemId);             // START: begins again from the first disc on the next tick
        } else {
            commitJukebox(g, list);
        }
        refreshButtons(g);
        p.sendActionBar(start ? Component.text("Музыка включена", NamedTextColor.GREEN)
                              : Component.text("Музыка выключена", NamedTextColor.RED));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Gui g)) return;
        if (!(e.getPlayer() instanceof Player p)) return;

        List<ItemStack> list = new ArrayList<>();
        for (int i = 0; i < SLOTS; i++) {
            ItemStack it = e.getInventory().getItem(i);
            if (it == null || it.getType().isAir()) continue;
            if (isDisc(it)) list.add(one(it));
            else giveBack(p, it);                                     // safety net: only discs may stay
        }
        if (g.itemId != null) closePortable(g, p, list);
        else closeJukebox(g, p, list);
    }

    private void giveBack(Player p, ItemStack it) {
        p.getInventory().addItem(it).values().forEach(r -> p.getWorld().dropItemNaturally(p.getLocation(), r));
    }

    private void closePortable(Gui g, Player p, List<ItemStack> list) {
        openItemGuis.remove(g.itemId);
        int idx = findPortable(p, g.itemId);
        if (idx < 0) {                       // the player no longer has the item: return the discs
            list.forEach(d -> giveBack(p, d));
            stopSession(g.itemId);
            return;
        }
        ItemStack it = p.getInventory().getItem(idx);
        writeItem(it, list);
        p.getInventory().setItem(idx, it);
        ItemStack before = g.before.isEmpty() ? null : g.before.get(0);
        ItemStack after = list.isEmpty() ? null : list.get(0);
        if (!same(before, after)) stopSession(g.itemId);        // new first disc -> restart on the next tick
    }

    // ------------------------------------------------------------------ jukebox block

    private void closeJukebox(Gui g, Player p, List<ItemStack> list) {
        openBlockGuis.remove(g.blockKey);
        if (!commitJukebox(g, list)) list.forEach(d -> giveBack(p, d));
    }

    /** stops whatever plays in the jukebox (vanilla song and/or CustomDiscs stream) */
    private void stopActive(Block b) {
        Jukebox live = (Jukebox) b.getState();
        if (live.isPlaying()) live.stopPlaying();
        if (bridge != null) bridge.stop(b);
        customStarts.remove(blockKey(b));
    }

    /** starts the disc that is in the jukebox: custom discs through CustomDiscs, vanilla ones normally */
    private void startFront(Block b, ItemStack front) {
        if (front.hasItemMeta()) {
            if (bridge == null) return;
            customStarts.put(blockKey(b), System.currentTimeMillis());
            bridge.play(b, front);
        } else {
            ((Jukebox) b.getState()).startPlaying();
        }
    }

    /** writes the menu contents + play/stop state into the jukebox; false if the jukebox is gone */
    private boolean commitJukebox(Gui g, List<ItemStack> list) {
        Block b = blockOf(g.blockKey);
        if (b == null || b.getType() != Material.JUKEBOX) return false;
        Jukebox j = (Jukebox) b.getState();
        ItemStack oldFront = j.hasRecord() ? j.getRecord() : null;

        if (list.isEmpty()) {
            stopActive(b);
            Jukebox c = (Jukebox) b.getState();
            c.setRecord(new ItemStack(Material.AIR));
            c.getPersistentDataContainer().remove(keyQueue);
            c.getPersistentDataContainer().remove(keyManaged);
            c.getPersistentDataContainer().remove(keyPaused);
            c.update(true, false);
            if (jukeboxes.remove(g.blockKey)) dirty = true;
            return true;
        }
        ItemStack front = list.get(0);
        List<ItemStack> rest = new ArrayList<>(list.subList(1, list.size()));
        if (jukeboxes.add(g.blockKey)) dirty = true;
        if (same(front, oldFront)) {                           // same first disc: don't interrupt it
            PersistentDataContainer pdc = j.getPersistentDataContainer();
            writeDiscs(pdc, keyQueue, rest);
            pdc.set(keyManaged, PersistentDataType.BYTE, (byte) 1);
            if (g.paused) pdc.set(keyPaused, PersistentDataType.BYTE, (byte) 1); else pdc.remove(keyPaused);
            j.update(true, false);
            Jukebox live = (Jukebox) b.getState();
            if (g.paused) stopActive(b);
            else if (!isActive(b, live)) startFront(b, front);
        } else {
            applyJukebox(b, front, rest, g.paused);
        }
        return true;
    }

    /** put {@code front} into the jukebox, store the rest as queue, start it unless paused */
    private void applyJukebox(Block b, ItemStack front, List<ItemStack> rest, boolean paused) {
        stopActive(b);                                         // the previous disc (if any) must not keep playing
        Jukebox j = (Jukebox) b.getState();
        PersistentDataContainer pdc = j.getPersistentDataContainer();
        writeDiscs(pdc, keyQueue, rest);
        pdc.set(keyManaged, PersistentDataType.BYTE, (byte) 1);
        if (paused) pdc.set(keyPaused, PersistentDataType.BYTE, (byte) 1); else pdc.remove(keyPaused);
        j.setRecord(front.clone());
        j.update(true, false);
        if (!paused) startFront(b, front);
    }

    private void tickJukeboxes() {
        Iterator<String> it = jukeboxes.iterator();
        while (it.hasNext()) {
            String key = it.next();
            Block b = blockOf(key);
            if (b == null) { it.remove(); dirty = true; continue; }
            if (!b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)) continue;
            if (b.getType() != Material.JUKEBOX) { it.remove(); customStarts.remove(key); dirty = true; continue; }
            if (openBlockGuis.contains(key)) continue;

            Jukebox j = (Jukebox) b.getState();
            PersistentDataContainer pdc = j.getPersistentDataContainer();
            if (!pdc.has(keyManaged, PersistentDataType.BYTE)) { it.remove(); dirty = true; continue; }
            if (pdc.has(keyPaused, PersistentDataType.BYTE)) continue;     // switched off with the Stop button
            if (isActive(b, j)) continue;

            ItemStack rec = j.hasRecord() ? j.getRecord() : null;
            Long started = customStarts.remove(key);
            if (started != null && System.currentTimeMillis() - started < CUSTOM_FAIL_MS) {
                // a custom disc stopped right after its start: switch off instead of looping through the queue
                pdc.set(keyPaused, PersistentDataType.BYTE, (byte) 1);
                j.update(true, false);
                getLogger().warning("Custom disc did not start at " + key + " (check CustomDiscs and Simple Voice Chat). Jukebox switched off.");
                continue;
            }

            List<ItemStack> rest = readDiscs(pdc, keyQueue);
            ItemStack front;
            if (rec != null && rec.getType().isRecord()) {
                if (rest.isEmpty()) { startFront(b, rec); continue; }      // a single disc just repeats
                rest.add(one(rec));                                         // played disc -> end of the queue
                front = rest.remove(0);
            } else if (!rest.isEmpty()) {
                front = rest.remove(0);
            } else {
                pdc.remove(keyManaged);
                j.update(true, false);
                it.remove(); dirty = true;
                continue;
            }
            applyJukebox(b, front, rest, false);
        }
    }

    /** discs waiting in the queue drop with the jukebox (the one inside drops by itself) */
    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() != Material.JUKEBOX) return;
        String key = blockKey(b);
        Jukebox j = (Jukebox) b.getState();
        for (ItemStack d : readDiscs(j.getPersistentDataContainer(), keyQueue))
            b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), d);
        customStarts.remove(key);
        if (jukeboxes.remove(key)) dirty = true;
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length >= 1 && a[0].equalsIgnoreCase("give")) {
            Player t = a.length >= 2 ? getServer().getPlayer(a[1]) : (s instanceof Player p ? p : null);
            if (t == null) { s.sendMessage("Player not found."); return true; }
            t.getInventory().addItem(createPortable()).values()
                    .forEach(r -> t.getWorld().dropItemNaturally(t.getLocation(), r));
            s.sendMessage("Given.");
            return true;
        }
        s.sendMessage("/discplayer give [player]");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        return a.length == 1 && "give".startsWith(a[0].toLowerCase()) ? List.of("give") : List.of();
    }
}
