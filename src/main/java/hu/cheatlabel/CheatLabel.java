package hu.cheatlabel;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Kiírja a játékos nametagje fölé, hogy milyen cheat clientet használ.
 * A felirat csak a cheatlabel.see jogosultsággal rendelkezőknek (alapból opok) látszik.
 */
public final class CheatLabel extends JavaPlugin implements Listener, TabExecutor {

    private static final String PERM_SEE = "cheatlabel.see";
    private static final String PERM_ADMIN = "cheatlabel.admin";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private record Signature(String id, String display, List<String> brands, List<String> channels) {}

    private final List<Signature> signatures = new ArrayList<>();
    private final Map<UUID, Signature> detected = new HashMap<>();
    private final Map<UUID, Integer> attempts = new HashMap<>();
    private final Map<UUID, TextDisplay> labels = new HashMap<>();
    private final Set<UUID> alerted = new HashSet<>();

    private double yOffset;
    private float scale;
    private boolean seeThrough;
    private boolean background;
    private boolean alertChat;
    private boolean alertConsole;
    private int maxAttempts;
    private String labelFormat;
    private String alertFormat;
    private BukkitTask task;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("cheatlabel");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        startTask();
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
        }
        for (UUID id : new ArrayList<>(labels.keySet())) {
            removeLabel(id);
        }
    }

    // ------------------------------------------------------------------ config

    private void loadSettings() {
        reloadConfig();
        yOffset = getConfig().getDouble("label.y-offset", 0.6);
        scale = (float) getConfig().getDouble("label.scale", 1.0);
        seeThrough = getConfig().getBoolean("label.see-through", false);
        background = getConfig().getBoolean("label.background", true);
        labelFormat = getConfig().getString("label.format", "<red><bold><client>");
        alertChat = getConfig().getBoolean("alerts.chat", true);
        alertConsole = getConfig().getBoolean("alerts.console", true);
        alertFormat = getConfig().getString("alerts.format",
                "<dark_red>[CheatLabel] <red><player> <gray>cheat clientet hasznal: <yellow><client>");
        maxAttempts = Math.max(1, getConfig().getInt("detection.max-attempts", 60));

        signatures.clear();
        ConfigurationSection clients = getConfig().getConfigurationSection("clients");
        if (clients != null) {
            for (String id : clients.getKeys(false)) {
                ConfigurationSection c = clients.getConfigurationSection(id);
                if (c == null) {
                    continue;
                }
                signatures.add(new Signature(
                        id,
                        c.getString("display", id),
                        lower(c.getStringList("brand")),
                        lower(c.getStringList("channels"))));
            }
        }
    }

    private static List<String> lower(List<String> in) {
        List<String> out = new ArrayList<>();
        for (String s : in) {
            if (s != null && !s.isBlank()) {
                out.add(s.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private void startTask() {
        if (task != null) {
            task.cancel();
        }
        long interval = Math.max(5, getConfig().getInt("detection.interval-ticks", 20));
        task = Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, interval);
    }

    // --------------------------------------------------------------- detection

    private Signature detect(Player p) {
        String brand = p.getClientBrandName();
        brand = brand == null ? "" : brand.toLowerCase(Locale.ROOT);
        Set<String> channels = p.getListeningPluginChannels();

        for (Signature s : signatures) {
            if (!brand.isEmpty()) {
                for (String b : s.brands()) {
                    if (brand.contains(b)) {
                        return s;
                    }
                }
            }
            for (String c : s.channels()) {
                for (String ch : channels) {
                    if (ch.toLowerCase(Locale.ROOT).contains(c)) {
                        return s;
                    }
                }
            }
        }
        return null;
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            Signature sig = detected.get(id);

            if (sig == null) {
                int n = attempts.merge(id, 1, Integer::sum);
                if (n > maxAttempts) {
                    continue;
                }
                sig = detect(p);
                if (sig == null) {
                    continue;
                }
                detected.put(id, sig);
                announce(p, sig);
            }
            ensureLabel(p, sig);
        }
    }

    private void announce(Player p, Signature sig) {
        if (!alerted.add(p.getUniqueId())) {
            return;
        }
        if (alertConsole) {
            getLogger().warning(p.getName() + " cheat clientet hasznal: " + sig.display());
        }
        if (alertChat) {
            Component msg = MM.deserialize(alertFormat,
                    Placeholder.unparsed("player", p.getName()),
                    Placeholder.unparsed("client", sig.display()));
            for (Player op : Bukkit.getOnlinePlayers()) {
                if (op.hasPermission(PERM_SEE)) {
                    op.sendMessage(msg);
                }
            }
        }
    }

    // ------------------------------------------------------------------ label

    private void ensureLabel(Player p, Signature sig) {
        if (p.isDead()) {
            return;
        }
        TextDisplay d = labels.get(p.getUniqueId());
        if (d == null || !d.isValid() || !p.getPassengers().contains(d)) {
            removeLabel(p.getUniqueId());
            d = spawnLabel(p, sig);
            labels.put(p.getUniqueId(), d);
        }
        syncVisibility(p, d);
    }

    private TextDisplay spawnLabel(Player p, Signature sig) {
        Component text = MM.deserialize(labelFormat, Placeholder.unparsed("client", sig.display()));
        TextDisplay d = p.getWorld().spawn(p.getLocation(), TextDisplay.class, display -> {
            // Alapból senkinek nem látszik, csak azoknak, akiknek showEntity-vel megmutatjuk.
            display.setVisibleByDefault(false);
            display.setPersistent(false);
            display.text(text);
            display.setBillboard(Display.Billboard.CENTER);
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            display.setSeeThrough(seeThrough);
            display.setShadowed(true);
            if (!background) {
                display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            }
            display.setTransformation(new Transformation(
                    new Vector3f(0f, (float) yOffset, 0f),
                    new AxisAngle4f(),
                    new Vector3f(scale, scale, scale),
                    new AxisAngle4f()));
        });
        p.addPassenger(d);
        return d;
    }

    private void syncVisibility(Player owner, TextDisplay d) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(owner)) {
                continue;
            }
            boolean allowed = viewer.hasPermission(PERM_SEE);
            boolean sees = viewer.canSee(d);
            if (allowed && !sees) {
                viewer.showEntity(this, d);
            } else if (!allowed && sees) {
                viewer.hideEntity(this, d);
            }
        }
    }

    private void removeLabel(UUID id) {
        TextDisplay d = labels.remove(id);
        if (d != null && d.isValid()) {
            d.remove();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        removeLabel(id);
        detected.remove(id);
        attempts.remove(id);
        alerted.remove(id);
    }

    // --------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("Hasznalat: /cheatlabel <list|check <jatekos>|reload>"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                if (!sender.hasPermission(PERM_ADMIN)) {
                    sender.sendMessage(Component.text("Nincs jogosultsagod."));
                    return true;
                }
                for (UUID id : new ArrayList<>(labels.keySet())) {
                    removeLabel(id);
                }
                detected.clear();
                attempts.clear();
                loadSettings();
                startTask();
                sender.sendMessage(Component.text("CheatLabel ujratoltve."));
            }
            case "list" -> {
                List<String> lines = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    Signature s = detected.get(p.getUniqueId());
                    if (s != null) {
                        lines.add(p.getName() + " - " + s.display());
                    }
                }
                sender.sendMessage(Component.text(lines.isEmpty()
                        ? "Jelenleg nincs megjelolt jatekos."
                        : "Megjelolt jatekosok:\n" + String.join("\n", lines)));
            }
            case "check" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Hasznalat: /cheatlabel check <jatekos>"));
                    return true;
                }
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t == null) {
                    sender.sendMessage(Component.text("A jatekos nincs online."));
                    return true;
                }
                String brand = t.getClientBrandName();
                Signature s = detect(t);
                sender.sendMessage(Component.text(
                        t.getName() + "\n"
                                + "Brand: " + (brand == null ? "(meg nem erkezett)" : brand) + "\n"
                                + "Csatornak: " + t.getListeningPluginChannels() + "\n"
                                + "Talalat: " + (s == null ? "nincs" : s.display())));
            }
            default -> sender.sendMessage(Component.text("Ismeretlen alparancs."));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("list", "check", "reload")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("check")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        }
        return out;
    }
}
