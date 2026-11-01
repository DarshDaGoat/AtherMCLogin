package com.athermc.auth;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AtherMCAuth extends JavaPlugin implements Listener {
    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> attempts = new ConcurrentHashMap<>();
    private final Set<UUID> processing = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BukkitTask> timers = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    private YamlConfiguration accounts;
    private java.io.File accountsFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        accountsFile = new java.io.File(getDataFolder(), "accounts.yml");
        if (!accountsFile.exists()) {
            try {
                getDataFolder().mkdirs();
                accountsFile.createNewFile();
            } catch (Exception ex) {
                getLogger().severe("Could not create accounts.yml: " + ex.getMessage());
            }
        }
        accounts = YamlConfiguration.loadConfiguration(accountsFile);
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("AtherMCAuth enabled. Keep regular backups of plugins/AtherMCAuth/accounts.yml.");
    }

    @Override
    public void onDisable() {
        for (BukkitTask task : timers.values()) task.cancel();
        timers.clear();
        saveAccounts();
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private String msg(String key) {
        return color(getConfig().getString("messages.prefix", "&cATHERMC &7» ")
                + getConfig().getString("messages." + key, "&cMissing message: " + key));
    }

    private void send(Player player, String key) {
        player.sendMessage(msg(key));
    }

    private void send(Player player, String key, Map<String, String> replacements) {
        String raw = getConfig().getString("messages.prefix", "&cATHERMC &7» ")
                + getConfig().getString("messages." + key, "&cMissing message: " + key);
        for (var entry : replacements.entrySet()) raw = raw.replace("%" + entry.getKey() + "%", entry.getValue());
        player.sendMessage(color(raw));
    }

    private String accountKey(String playerName) {
        return "accounts." + playerName.toLowerCase(Locale.ROOT);
    }

    private boolean hasAccount(String playerName) {
        return accounts.contains(accountKey(playerName) + ".hash")
                && accounts.contains(accountKey(playerName) + ".salt");
    }

    private void saveAccounts() {
        try {
            accounts.save(accountsFile);
        } catch (Exception ex) {
            getLogger().severe("Could not save accounts.yml: " + ex.getMessage());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        authenticated.remove(id);
        attempts.put(id, 0);
        processing.remove(id);
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(player) && !authenticated.contains(other.getUniqueId())) {
                other.hidePlayer(this, player);
                player.hidePlayer(this, other);
            } else if (!other.equals(player)) {
                other.hidePlayer(this, player);
                player.hidePlayer(this, other);
            }
        }

        int timeout = Math.max(10, getConfig().getInt("settings.login-timeout-seconds", 60));
        if (hasAccount(player.getName())) {
            send(player, "join-login", Map.of("attempts",
                    String.valueOf(getConfig().getInt("settings.max-login-attempts", 3))));
        } else {
            send(player, "join-register");
        }
        startTimer(player, timeout);
    }

    private void startTimer(Player player, int seconds) {
        UUID id = player.getUniqueId();
        BukkitTask old = timers.remove(id);
        if (old != null) old.cancel();
        final int[] remaining = {seconds};
        timers.put(id, Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!player.isOnline() || authenticated.contains(id)) {
                BukkitTask task = timers.remove(id);
                if (task != null) task.cancel();
                return;
            }
            String bar = getConfig().getString("messages.actionbar",
                    "&cLogin required &7| &fTime left: &e%seconds%s")
                    .replace("%seconds%", String.valueOf(remaining[0]));
            player.sendActionBar(color(bar));
            if (remaining[0] <= 0) {
                player.kickPlayer(color(getConfig().getString("messages.timeout", "&cYou took too long to log in.")));
                BukkitTask task = timers.remove(id);
                if (task != null) task.cancel();
                return;
            }
            int interval = Math.max(5, getConfig().getInt("settings.reminder-interval-seconds", 10));
            if (remaining[0] < seconds && remaining[0] % interval == 0) {
                send(player, hasAccount(player.getName()) ? "reminder" : "join-register");
            }
            remaining[0]--;
        }, 0L, 20L));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        authenticated.remove(id);
        attempts.remove(id);
        processing.remove(id);
        BukkitTask task = timers.remove(id);
        if (task != null) task.cancel();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used in-game.");
            return true;
        }

        String commandName = command.getName().toLowerCase(Locale.ROOT);
        if (commandName.equals("login")) {
            if (authenticated.contains(player.getUniqueId())) {
                send(player, "already-logged-in");
                return true;
            }
            if (args.length != 1) {
                send(player, "login-usage");
                return true;
            }
            if (!hasAccount(player.getName())) {
                send(player, "not-registered");
                return true;
            }
            if (!processing.add(player.getUniqueId())) {
                player.sendMessage(color("&cPlease wait for the previous attempt to finish."));
                return true;
            }
            String name = player.getName();
            String supplied = args[0];
            String salt = accounts.getString(accountKey(name) + ".salt", "");
            String expected = accounts.getString(accountKey(name) + ".hash", "");
            int iterations = accounts.getInt(accountKey(name) + ".iterations",
                    getConfig().getInt("settings.pbkdf2-iterations", 210000));
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                boolean ok = verifyPassword(supplied, salt, expected, iterations);
                Bukkit.getScheduler().runTask(this, () -> {
                    processing.remove(player.getUniqueId());
                    if (!player.isOnline() || authenticated.contains(player.getUniqueId())) return;
                    if (ok) {
                        authenticate(player);
                    } else {
                        int left = Math.max(0, getConfig().getInt("settings.max-login-attempts", 3)
                                - attempts.merge(player.getUniqueId(), 1, Integer::sum));
                        if (left <= 0) {
                            send(player, "too-many-attempts");
                            player.kickPlayer(color("&cToo many failed login attempts."));
                        } else {
                            send(player, "login-failed", Map.of("attempts", String.valueOf(left)));
                        }
                    }
                });
            });
            return true;
        }

        if (commandName.equals("register")) {
            if (authenticated.contains(player.getUniqueId())) {
                send(player, "already-logged-in");
                return true;
            }
            if (hasAccount(player.getName())) {
                send(player, "already-registered");
                return true;
            }
            if (args.length != 2) {
                send(player, "register-usage");
                return true;
            }
            String password = args[0];
            String confirm = args[1];
            if (!password.equals(confirm)) {
                send(player, "password-mismatch");
                return true;
            }
            int min = getConfig().getInt("settings.minimum-password-length", 8);
            int max = getConfig().getInt("settings.maximum-password-length", 64);
            if (password.length() < min) {
                send(player, "password-too-short", Map.of("min", String.valueOf(min)));
                return true;
            }
            if (password.length() > max) {
                send(player, "password-too-long", Map.of("max", String.valueOf(max)));
                return true;
            }
            if (!processing.add(player.getUniqueId())) {
                player.sendMessage(color("&cPlease wait for the previous operation to finish."));
                return true;
            }
            String name = player.getName();
            byte[] saltBytes = new byte[16];
            random.nextBytes(saltBytes);
            String salt = Base64.getEncoder().encodeToString(saltBytes);
            int iterations = getConfig().getInt("settings.pbkdf2-iterations", 210000);
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                String hash = hashPassword(password, saltBytes, iterations);
                Bukkit.getScheduler().runTask(this, () -> {
                    processing.remove(player.getUniqueId());
                    if (!player.isOnline() || authenticated.contains(player.getUniqueId())) return;
                    if (hasAccount(name)) {
                        send(player, "already-registered");
                        return;
                    }
                    String key = accountKey(name);
                    accounts.set(key + ".name", name);
                    accounts.set(key + ".salt", salt);
                    accounts.set(key + ".hash", hash);
                    accounts.set(key + ".iterations", iterations);
                    saveAccounts();
                    authenticate(player);
                    send(player, "register-success");
                });
            });
            return true;
        }
        return false;
    }

    private void authenticate(Player player) {
        UUID id = player.getUniqueId();
        authenticated.add(id);
        attempts.remove(id);
        BukkitTask task = timers.remove(id);
        if (task != null) task.cancel();
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(player) && authenticated.contains(other.getUniqueId())) {
                other.showPlayer(this, player);
                player.showPlayer(this, other);
            }
        }
        send(player, "login-success");
    }

    private String hashPassword(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try {
            byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception ex) {
            throw new IllegalStateException("Password hashing failed", ex);
        } finally {
            spec.clearPassword();
        }
    }

    private boolean verifyPassword(String password, String salt, String expected, int iterations) {
        try {
            byte[] saltBytes = Base64.getDecoder().decode(salt);
            byte[] expectedBytes = Base64.getDecoder().decode(expected);
            byte[] actualBytes = Base64.getDecoder().decode(hashPassword(password, saltBytes, iterations));
            return MessageDigest.isEqual(expectedBytes, actualBytes);
        } catch (Exception ex) {
            getLogger().warning("Could not verify password: " + ex.getMessage());
            return false;
        }
    }

    private boolean isAuthenticated(Player player) {
        return authenticated.contains(player.getUniqueId());
    }

    private void blockIfUnauthenticated(Player player, org.bukkit.event.Cancellable event) {
        if (!isAuthenticated(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent event) {
        if (!isAuthenticated(event.getPlayer()) && event.getTo() != null) {
            Location from = event.getFrom();
            Location to = event.getTo();
            if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
                event.setTo(from);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) { blockIfUnauthenticated(event.getPlayer(), event); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) { blockIfUnauthenticated(event.getPlayer(), event); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) { blockIfUnauthenticated(event.getPlayer(), event); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) { blockIfUnauthenticated(event.getPlayer(), event); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickup(PlayerPickupItemEvent event) { blockIfUnauthenticated(event.getPlayer(), event); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwap(PlayerSwapHandItemsEvent event) { blockIfUnauthenticated(event.getPlayer(), event); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHeld(PlayerItemHeldEvent event) { blockIfUnauthenticated(event.getPlayer(), event); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onConsume(PlayerItemConsumeEvent event) { blockIfUnauthenticated(event.getPlayer(), event); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && !isAuthenticated(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && !isAuthenticated(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!isAuthenticated(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (isAuthenticated(player)) {
            if (event.getMessage().toLowerCase(Locale.ROOT).startsWith("/register ")
                    || event.getMessage().equalsIgnoreCase("/register")) {
                event.setCancelled(true);
                send(player, "already-logged-in");
            }
            return;
        }
        String raw = event.getMessage().toLowerCase(Locale.ROOT).split(" ")[0];
        if (!raw.equals("/login") && !raw.equals("/l")
                && !raw.equals("/register") && !raw.equals("/reg")) {
            event.setCancelled(true);
            send(player, "must-login");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && !isAuthenticated(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && !isAuthenticated(player)) event.setCancelled(true);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return java.util.Collections.emptyList();
    }
}
