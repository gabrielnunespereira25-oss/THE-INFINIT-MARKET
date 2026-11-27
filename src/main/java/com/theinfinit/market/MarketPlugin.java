package com.theinfinit.market;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public final class MarketPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, ItemStack[]> drafts = new HashMap<>();
    private final Map<String, Offer> offers = new LinkedHashMap<>();
    private final Map<UUID, List<ItemStack>> mailbox = new HashMap<>();
    private final Map<UUID, String> pending = new HashMap<>();

    private static final int PRODUCT = 13;
    private static final int[] PAYMENT = {28,29,30,31,32,33,34};

    private static class Offer {
        UUID seller;
        String sellerName;
        ItemStack product;
        List<ItemStack> payment;
        long expires;

        Offer(UUID seller, String name, ItemStack product,
              List<ItemStack> payment) {
            this.seller = seller;
            this.sellerName = name;
            this.product = product.clone();
            this.payment = new ArrayList<>();
            for (ItemStack item : payment)
                this.payment.add(item.clone());
            this.expires = System.currentTimeMillis()
                    + 7L * 24 * 60 * 60 * 1000;
        }
    }

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("loja")).setExecutor(this);
        Objects.requireNonNull(getCommand("store")).setExecutor(this);
        loadOffers();

        Bukkit.getScheduler().runTaskTimer(this, this::expireOffers,
                1200L, 72000L);

        getLogger().info("THE INFINIT MARKET iniciado.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (!(sender instanceof Player p)) return true;

        if (command.getName().equalsIgnoreCase("loja")) {
            openMarket(p);
            return true;
        }

        if (args.length == 0) {
            p.sendMessage("§e/store criar §7- Criar oferta");
            p.sendMessage("§e/store correio §7- Receber itens");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "criar" -> openEditor(p);
            case "correio" -> claimMailbox(p);
            case "npc" -> {
                if (!p.hasPermission("theinfinitmarket.admin")) {
                    p.sendMessage("§cSem permissão.");
                    return true;
                }
                var villager = p.getWorld().spawn(
                        p.getLocation(),
                        org.bukkit.entity.Villager.class);
                villager.setCustomName("§6THE INFINIT MARKET");
                villager.setCustomNameVisible(true);
                villager.setAI(false);
                villager.setInvulnerable(true);
                p.sendMessage("§aNPC criado.");
            }
            default -> p.sendMessage("§cComando desconhecido.");
        }
        return true;
    }

    private ItemStack button(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    private void openEditor(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54,
                "§8Editor de oferta");

        inv.setItem(4, button(Material.PAPER,
                "§eProduto no centro; pagamento embaixo"));
        inv.setItem(49, button(Material.LIME_CONCRETE,
                "§aPUBLICAR OFERTA"));
        inv.setItem(45, button(Material.BARRIER, "§cCancelar"));

        drafts.put(p.getUniqueId(), new ItemStack[54]);
        p.openInventory(inv);
    }

    private void openMarket(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54,
                "§8Mercado público");

        int slot = 0;
        for (var entry : offers.entrySet()) {
            if (slot >= 45) break;

            ItemStack display = entry.getValue().product.clone();
            ItemMeta meta = display.getItemMeta();
            List<String> lore = new ArrayList<>();
            lore.add("§7Vendedor: " + entry.getValue().sellerName);
            lore.add("§eClique para ver a oferta");
            meta.setLore(lore);
            display.setItemMeta(meta);
            inv.setItem(slot++, display);
        }

        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        String title = e.getView().getTitle();

        if (title.equals("§8Editor de oferta")) {
            int slot = e.getRawSlot();

            if (slot == 49) {
                e.setCancelled(true);
                publish(p);
                return;
            }

            if (slot == 45) {
                e.setCancelled(true);
                p.closeInventory();
                return;
            }

            boolean allowed = slot == PRODUCT;
            for (int s : PAYMENT)
                if (slot == s) allowed = true;

            if (slot < 54 && !allowed)
                e.setCancelled(true);

            if (slot >= 54 && e.isShiftClick())
                e.setCancelled(true);

            return;
        }

        if (title.equals("§8Mercado público")) {
            e.setCancelled(true);
            ItemStack clicked = e.getCurrentItem();
            if (clicked == null || clicked.getType().isAir()) return;

            int index = e.getRawSlot();
            List<String> ids = new ArrayList<>(offers.keySet());

            if (index >= 0 && index < ids.size()) {
                String id = ids.get(index);
                pending.put(p.getUniqueId(), id);
                openOffer(p, id);
            }
            return;
        }

        if (title.equals("§8Confirmar troca")) {
            e.setCancelled(true);
            if (e.getRawSlot() == 13) {
                String id = pending.get(p.getUniqueId());
                if (id != null) purchase(p, id);
            }
        }
    }

    private void publish(Player p) {
        Inventory inv = p.getOpenInventory().getTopInventory();
        ItemStack product = inv.getItem(PRODUCT);

        if (product == null || product.getType().isAir()) {
            p.sendMessage("§cColoque o produto no espaço central.");
            return;
        }

        List<ItemStack> payment = new ArrayList<>();
        for (int s : PAYMENT) {
            ItemStack item = inv.getItem(s);
            if (item != null && !item.getType().isAir())
                payment.add(item.clone());
        }

        if (payment.isEmpty()) {
            p.sendMessage("§cAdicione o pagamento.");
            return;
        }

        String id = UUID.randomUUID().toString();
        offers.put(id, new Offer(p.getUniqueId(), p.getName(),
                product, payment));

        inv.setItem(PRODUCT, null);
        for (int s : PAYMENT) inv.setItem(s, null);

        p.closeInventory();
        p.sendMessage("§aOferta publicada por 7 dias!");
        saveOffers();
    }

    private void openOffer(Player p, String id) {
        Offer offer = offers.get(id);
        if (offer == null) return;

        Inventory inv = Bukkit.createInventory(null, 27,
                "§8Confirmar troca");

        inv.setItem(11, offer.product.clone());
        inv.setItem(13, button(Material.LIME_CONCRETE,
                "§aCONFIRMAR COMPRA"));

        int slot = 15;
        for (ItemStack item : offer.payment) {
            if (slot < 22) inv.setItem(slot++, item.clone());
        }

        p.openInventory(inv);
    }

    private void purchase(Player buyer, String id) {
        Offer offer = offers.get(id);
        if (offer == null) {
            buyer.sendMessage("§cOferta não encontrada.");
            return;
        }

        if (buyer.getUniqueId().equals(offer.seller)) {
            buyer.sendMessage("§cVocê não pode comprar sua própria oferta.");
            return;
        }

        for (ItemStack need : offer.payment) {
            if (countItems(buyer, need) < need.getAmount()) {
                buyer.sendMessage("§cVocê não tem todos os itens necessários.");
                return;
            }
        }

        for (ItemStack need : offer.payment)
            removeItems(buyer, need);

        Map<Integer, ItemStack> overflow =
                buyer.getInventory().addItem(offer.product.clone());

        for (ItemStack item : overflow.values())
            buyer.getWorld().dropItemNaturally(buyer.getLocation(), item);

        Player seller = Bukkit.getPlayer(offer.seller);

        if (seller != null) {
            for (ItemStack item : offer.payment)
                giveOrMail(seller, item);
        } else {
            mailbox.computeIfAbsent(offer.seller,
                    k -> new ArrayList<>()).addAll(cloneItems(offer.payment));
        }

        offers.remove(id);
        pending.remove(buyer.getUniqueId());
        saveOffers();

        buyer.closeInventory();
        buyer.sendMessage("§aTroca concluída!");

        if (seller != null)
            seller.sendMessage("§aSua oferta foi vendida!");
    }

    private int countItems(Player p, ItemStack target) {
        int count = 0;
        for (ItemStack item : p.getInventory().getStorageContents()) {
            if (item != null && item.isSimilar(target))
                count += item.getAmount();
        }
        return count;
    }

    private void removeItems(Player p, ItemStack target) {
        int remaining = target.getAmount();

        for (int i = 0; i < 36 && remaining > 0; i++) {
            ItemStack item = p.getInventory().getItem(i);

            if (item != null && item.isSimilar(target)) {
                int take = Math.min(remaining, item.getAmount());
                item.setAmount(item.getAmount() - take);

                if (item.getAmount() <= 0)
                    p.getInventory().setItem(i, null);

                remaining -= take;
            }
        }
    }

    private void giveOrMail(Player p, ItemStack item) {
        Map<Integer, ItemStack> extra =
                p.getInventory().addItem(item.clone());

        for (ItemStack leftover : extra.values()) {
            mailbox.computeIfAbsent(p.getUniqueId(),
                    k -> new ArrayList<>()).add(leftover);
        }
    }

    private List<ItemStack> cloneItems(List<ItemStack> items) {
        List<ItemStack> copy = new ArrayList<>();
        for (ItemStack item : items) copy.add(item.clone());
        return copy;
    }

    private void claimMailbox(Player p) {
        List<ItemStack> items = mailbox.get(p.getUniqueId());

        if (items == null || items.isEmpty()) {
            p.sendMessage("§7Seu correio está vazio.");
            return;
        }

        Iterator<ItemStack> iterator = items.iterator();
        while (iterator.hasNext()) {
            ItemStack item = iterator.next();
            Map<Integer, ItemStack> extra =
                    p.getInventory().addItem(item.clone());

            if (extra.isEmpty()) iterator.remove();
        }

        if (items.isEmpty()) mailbox.remove(p.getUniqueId());
        saveOffers();
        p.sendMessage("§aCorreio atualizado. Confira seu inventário.");
    }

    private void expireOffers() {
        long now = System.currentTimeMillis();

        Iterator<Map.Entry<String, Offer>> iterator =
                offers.entrySet().iterator();

        while (iterator.hasNext()) {
            Offer offer = iterator.next().getValue();

            if (offer.expires <= now) {
                mailbox.computeIfAbsent(offer.seller,
                        k -> new ArrayList<>()).add(offer.product.clone());
                iterator.remove();
            }
        }

        saveOffers();
    }

    private void saveOffers() {
        getConfig().set("offers", null);
        for (var entry : offers.entrySet()) {
            String path = "offers." + entry.getKey();
            Offer o = entry.getValue();

            getConfig().set(path + ".seller", o.seller.toString());
            getConfig().set(path + ".name", o.sellerName);
            getConfig().set(path + ".product", o.product);
            getConfig().set(path + ".payment", o.payment);
            getConfig().set(path + ".expires", o.expires);
        }

        getConfig().set("mailbox", null);
        for (var entry : mailbox.entrySet())
            getConfig().set("mailbox." + entry.getKey(), entry.getValue());

        saveConfig();
    }

    private void loadOffers() {
        if (getConfig().getConfigurationSection("offers") != null) {
            for (String id : getConfig().getConfigurationSection("offers").getKeys(false)) {
                String path = "offers." + id;
                try {
                    Offer o = new Offer(
                            UUID.fromString(getConfig().getString(path + ".seller")),
                            getConfig().getString(path + ".name", "Jogador"),
                            getConfig().getItemStack(path + ".product"),
                            (List<ItemStack>) getConfig().getList(path + ".payment")
                    );
                    o.expires = getConfig().getLong(path + ".expires");
                    offers.put(id, o);
                } catch (Exception ex) {
                    getLogger().warning("Oferta inválida ignorada: " + id);
                }
            }
        }

        if (getConfig().getConfigurationSection("mailbox") != null) {
            for (String id : getConfig().getConfigurationSection("mailbox").getKeys(false)) {
                try {
                    mailbox.put(UUID.fromString(id),
                            (List<ItemStack>) getConfig().getList("mailbox." + id));
                } catch (Exception ignored) {}
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        if (!e.getView().getTitle().equals("§8Editor de oferta")) return;

        Inventory inv = e.getView().getTopInventory();

        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && !item.getType().isAir()) {
                Map<Integer, ItemStack> extra =
                        p.getInventory().addItem(item);
                for (ItemStack leftover : extra.values())
                    p.getWorld().dropItemNaturally(p.getLocation(), leftover);
            }
        }
    }
}
