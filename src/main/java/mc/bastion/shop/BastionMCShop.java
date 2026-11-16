package mc.bastion.shop;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BastionMCShop extends JavaPlugin implements Listener {
    private Economy economy;
    private FileConfiguration catalog;
    private final Map<Material, Product> products = new LinkedHashMap<>();
    private final Map<String, Category> categories = new LinkedHashMap<>();
    private final Set<Material> banned = new HashSet<>();
    private final MiniMessage mini = MiniMessage.miniMessage();
    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");
    private static final Pattern LEGACY = Pattern.compile("&([0-9a-fA-Fk-orK-OR])");

    private record Product(Material material, double buy, Double sellOverride, String category) {
        double sell(double percentage) { return sellOverride == null ? buy * percentage / 100d : sellOverride; }
    }
    private record Category(String key, String name, Material icon, List<Material> materials) {}
    private enum View { ROOT, CATEGORY, PRODUCT, CONFIRM }
    private static final class ShopHolder implements InventoryHolder {
        final View view; final String category; final Material product; final int page;
        Inventory inventory;
        ShopHolder(View view, String category, Material product, int page) {
            this.view=view; this.category=category; this.product=product; this.page=page;
        }
        @Override public @NotNull Inventory getInventory() { return inventory; }
    }
    private record SaleLine(int slot, Material material, int quantity, double total) {}
    private record Sale(List<SaleLine> lines, int quantity, double total) {}

    @Override public void onEnable() {
        saveDefaultConfig();
        saveResource("catalog.yml", false);
        if (!setupEconomy()) { getLogger().severe("Vault no tiene proveedor de economia. Se desactiva BastionMCShop."); getServer().getPluginManager().disablePlugin(this); return; }
        loadCatalog();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("BastionMCShop iniciado: " + categories.size() + " categorias, " + products.size() + " productos.");
    }
    private boolean setupEconomy() {
        RegisteredServiceProvider<Economy> provider = getServer().getServicesManager().getRegistration(Economy.class);
        economy = provider == null ? null : provider.getProvider();
        return economy != null;
    }
    private void loadCatalog() {
        catalog = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "catalog.yml"));
        products.clear(); categories.clear(); banned.clear();
        for (String s : catalog.getStringList("blacklist")) {
            Material m=Material.matchMaterial(s); if (m!=null) banned.add(m);
        }
        // Bloqueos inmutables aunque alguien borre la blacklist del YAML.
        for (String s : List.of("SPAWNER","TRIAL_SPAWNER","VAULT","BOOK","WRITABLE_BOOK","WRITTEN_BOOK","ENCHANTED_BOOK","PLAYER_HEAD","ZOMBIE_HEAD","SKELETON_SKULL","WITHER_SKELETON_SKULL","CREEPER_HEAD","DRAGON_HEAD","PIGLIN_HEAD")) {
            Material m=Material.matchMaterial(s); if(m!=null) banned.add(m);
        }
        ConfigurationSection all=catalog.getConfigurationSection("categories");
        if(all==null) return;
        for(String key:all.getKeys(false)) {
            ConfigurationSection section=all.getConfigurationSection(key);
            if(section==null) continue;
            Material icon=Material.matchMaterial(section.getString("icon", "CHEST"));
            if(icon==null || !icon.isItem()) icon=Material.CHEST;
            String name=section.getString("name",key);
            List<Material> materials=new ArrayList<>();
            ConfigurationSection items=section.getConfigurationSection("items");
            if(items!=null) for(String itemName:items.getKeys(false)) {
                Material m=Material.matchMaterial(itemName);
                if(m==null || !m.isItem() || banned.contains(m)) { getLogger().warning("Producto ignorado: " + itemName); continue; }
                double price; Double sellOverride=null;
                if(items.isConfigurationSection(itemName)) {
                    price=items.getDouble(itemName+".buy-price",-1);
                    if(items.contains(itemName+".sell-price")) sellOverride=items.getDouble(itemName+".sell-price");
                } else price=items.getDouble(itemName,-1);
                if(!Double.isFinite(price) || price<=0 || (sellOverride!=null && (!Double.isFinite(sellOverride) || sellOverride<0))) {
                    getLogger().warning("Precio invalido: " + itemName); continue;
                }
                if(products.containsKey(m)) {getLogger().warning("Producto duplicado: " + itemName);continue;}
                products.put(m,new Product(m,price,sellOverride,key)); materials.add(m);
            }
            categories.put(key,new Category(key,name,icon,materials));
        }
    }
    private Component text(String value) {
        String s=value == null ? "" : value;
        Matcher hex=HEX.matcher(s); StringBuffer b=new StringBuffer();
        while(hex.find()) hex.appendReplacement(b,Matcher.quoteReplacement("<color:#"+hex.group(1)+">"));
        hex.appendTail(b); s=b.toString();
        // Legacy ampersand formatting, including &l. MiniMessage tags also supported.
        Map<Character,String> colors=Map.ofEntries(Map.entry('0',"black"),Map.entry('1',"dark_blue"),Map.entry('2',"dark_green"),Map.entry('3',"dark_aqua"),Map.entry('4',"dark_red"),Map.entry('5',"dark_purple"),Map.entry('6',"gold"),Map.entry('7',"gray"),Map.entry('8',"dark_gray"),Map.entry('9',"blue"),Map.entry('a',"green"),Map.entry('b',"aqua"),Map.entry('c',"red"),Map.entry('d',"light_purple"),Map.entry('e',"yellow"),Map.entry('f',"white"),Map.entry('l',"bold"),Map.entry('o',"italic"),Map.entry('n',"underlined"),Map.entry('m',"strikethrough"),Map.entry('r',"reset"));
        Matcher legacy=LEGACY.matcher(s); b=new StringBuffer();
        while(legacy.find()) legacy.appendReplacement(b,Matcher.quoteReplacement("<"+colors.getOrDefault(Character.toLowerCase(legacy.group(1).charAt(0)),"reset")+">"));
        legacy.appendTail(b);
        try {return mini.deserialize(b.toString());} catch(Exception e) {return Component.text(value);}
    }
    private String money(double amount) { return getConfig().getString("economy.currency-symbol","$") + String.format(Locale.US,"%,.2f",amount); }
    private void message(Player player,String key,String... pairs) {
        String s=getConfig().getString("messages.prefix","") + getConfig().getString("messages."+key,key);
        for(int i=0;i+1<pairs.length;i+=2) s=s.replace("{"+pairs[i]+"}",pairs[i+1]);
        player.sendMessage(text(s));
    }
    private Inventory inventory(View view,String category,Material product,int page,int size,Component title) {
        ShopHolder holder=new ShopHolder(view,category,product,page);
        Inventory inv=Bukkit.createInventory(holder,size,title);holder.inventory=inv;return inv;
    }
    private ItemStack icon(Material m,String name,String... lore) {
        ItemStack item=new ItemStack(m);ItemMeta meta=item.getItemMeta();
        meta.displayName(text(name));
        if(lore.length>0) meta.lore(Arrays.stream(lore).map(this::text).toList());
        item.setItemMeta(meta);return item;
    }
    private ItemStack nav(String material,String name,String... lore) {
        Material m=Material.matchMaterial(getConfig().getString("shop."+material,"ARROW"));
        return icon(m==null||!m.isItem()?Material.ARROW:m,name,lore);
    }
    private void openRoot(Player player,int page) {
        List<Category> list=new ArrayList<>(categories.values());int pages=Math.max(1,(list.size()+44)/45);page=Math.max(0,Math.min(page,pages-1));
        Inventory inv=inventory(View.ROOT,null,null,page,54,text(getConfig().getString("shop.title","BASTION SHOP")));
        for(int i=0;i<45&&page*45+i<list.size();i++) {
            Category c=list.get(page*45+i);inv.setItem(i,icon(c.icon,c.name,"<gray>Click para ver productos"));
        }
        if(page>0)inv.setItem(45,nav("previous-material","<yellow>Anterior"));
        if(page+1<pages)inv.setItem(53,nav("next-material","<green>Siguiente"));
        inv.setItem(49,icon(Material.PAPER,"<yellow>Página "+(page+1)+"/"+pages));player.openInventory(inv);
    }
    private void openCategory(Player player,String key,int page) {
        Category c=categories.get(key);if(c==null){openRoot(player,0);return;}
        int pages=Math.max(1,(c.materials.size()+44)/45);page=Math.max(0,Math.min(page,pages-1));
        String title=getConfig().getString("shop.category-title","{category} ({page}/{pages})").replace("{category}",c.name).replace("{page}",""+(page+1)).replace("{pages}",""+pages);
        Inventory inv=inventory(View.CATEGORY,key,null,page,54,text(title));
        for(int i=0;i<45&&page*45+i<c.materials.size();i++) {
            Material m=c.materials.get(page*45+i);Product p=products.get(m);
            inv.setItem(i,icon(m,"<white>"+pretty(m),"<green>Comprar: "+money(p.buy),"<red>Vender: "+money(sellPrice(p)),"<yellow>Click para ver opciones"));
        }
        inv.setItem(48,nav("back-material","<gold>Volver a categorías"));
        if(page>0)inv.setItem(45,nav("previous-material","<yellow>Anterior"));
        if(page+1<pages)inv.setItem(53,nav("next-material","<green>Siguiente"));
        inv.setItem(49,icon(Material.PAPER,"<yellow>Página "+(page+1)+"/"+pages));player.openInventory(inv);
    }
    private void openProduct(Player player,Material m,int categoryPage) {
        Product p=products.get(m);if(p==null)return;
        String title=getConfig().getString("shop.product-title","{item}").replace("{item}",pretty(m));
        Inventory inv=inventory(View.PRODUCT,p.category,m,categoryPage,54,text(title));
        int max=m.getMaxStackSize();
        inv.setItem(13,icon(m,"<white>"+pretty(m),"<green>Comprar 1: "+money(p.buy),"<red>Vender 1: "+money(sellPrice(p))));
        int[] amounts={1,16,32,max};int[] buys={20,21,23,24},sells={29,30,32,33};
        for(int i=0;i<4;i++){
            int q=Math.min(amounts[i],max);
            inv.setItem(buys[i],nav("buy-material","<green>COMPRAR x"+q));
            inv.setItem(sells[i],nav("sell-material","<red>VENDER x"+q));
        }
        inv.setItem(49,nav("back-material","<gold>Volver"));player.openInventory(inv);
    }
    private String pretty(Material m) {return m.name().replace('_',' ');}
    private double sellPrice(Product p) {return p.sell(getConfig().getDouble("economy.sell-percentage",50));}
    private boolean eligible(ItemStack item) {
        if(item==null || item.getType().isAir() || banned.contains(item.getType()) || !products.containsKey(item.getType()))return false;
        if(item.hasItemMeta()) {
            ItemMeta meta=item.getItemMeta();
            if(getConfig().getBoolean("sellall.ignore-renamed-items",true) && meta.hasDisplayName())return false;
            if(getConfig().getBoolean("sellall.ignore-enchanted-items",true) && meta.hasEnchants())return false;
            if(getConfig().getBoolean("sellall.ignore-custom-items",true) && (meta.hasCustomModelData() || !meta.getPersistentDataContainer().isEmpty() || meta.hasLore()))return false;
        }
        return true;
    }
    private double round(double value){return BigDecimal.valueOf(value).setScale(2,RoundingMode.HALF_UP).doubleValue();}
    private void buy(Player player,Product p,int quantity) {
        double total=round(p.buy*quantity);
        if(!economy.has(player,total)){message(player,"no-money","amount",money(total));return;}
        ItemStack stack=new ItemStack(p.material,quantity);
        if(!player.getInventory().addItem(stack.clone()).isEmpty()) {
            // addItem mutates inventory partially; roll back using preflight instead (see below)
            // This branch is unreachable after the preflight guard in the click handler.
            message(player,"no-space");return;
        }
        EconomyResponse r=economy.withdrawPlayer(player,total);
        if(!r.transactionSuccess()) {
            player.getInventory().removeItem(stack);
            message(player,"sale-failed");return;
        }
        message(player,"bought","quantity",""+quantity,"item",pretty(p.material),"amount",money(total));
    }
    private boolean canFit(Player player,Material m,int quantity) {
        int capacity=0;for(ItemStack stack:player.getInventory().getStorageContents()) {
            if(stack==null||stack.getType().isAir())capacity+=m.getMaxStackSize();
            else if(stack.getType()==m && eligible(stack))capacity+=m.getMaxStackSize()-stack.getAmount();
        }return capacity>=quantity;
    }
    private void safeBuy(Player player,Product p,int quantity) {
        if(!canFit(player,p.material,quantity)){message(player,"no-space");return;}
        double total=round(p.buy*quantity);
        if(!economy.has(player,total)){message(player,"no-money","amount",money(total));return;}
        EconomyResponse r=economy.withdrawPlayer(player,total);
        if(!r.transactionSuccess()){message(player,"sale-failed");return;}
        Map<Integer,ItemStack> overflow=player.getInventory().addItem(new ItemStack(p.material,quantity));
        if(!overflow.isEmpty()) {
            // Unexpected inventory change: refund and remove only the items that were just added.
            // Normal Bukkit event execution is synchronous; this is a defensive branch.
            economy.depositPlayer(player,total);
            getLogger().severe("Unexpected inventory overflow during purchase by " + player.getName());
            message(player,"sale-failed");return;
        }
        message(player,"bought","quantity",""+quantity,"item",pretty(p.material),"amount",money(total));
    }
    private void sell(Player player,Product p,int quantity) {
        int available=0;for(ItemStack item:player.getInventory().getStorageContents())if(item!=null&&item.getType()==p.material&&eligible(item))available+=item.getAmount();
        if(available<quantity){message(player,"no-items");return;}
        double total=round(sellPrice(p)*quantity);
        if(total<=0){message(player,"sale-failed");return;}
        EconomyResponse r=economy.depositPlayer(player,total);
        if(!r.transactionSuccess()){message(player,"sale-failed");return;}
        int left=quantity;
        for(int i=0;i<36&&left>0;i++){
            ItemStack item=player.getInventory().getItem(i);
            if(item==null||item.getType()!=p.material||!eligible(item))continue;
            int remove=Math.min(left,item.getAmount());left-=remove;
            if(item.getAmount()==remove)player.getInventory().setItem(i,null);
            else item.setAmount(item.getAmount()-remove);
        }
        message(player,"sold","quantity",""+quantity,"item",pretty(p.material),"amount",money(total));
    }
    private Sale scan(Player player) {
        List<SaleLine> lines=new ArrayList<>();int count=0;double total=0;
        for(int i=0;i<36;i++) {
            ItemStack item=player.getInventory().getItem(i);
            if(!eligible(item))continue;
            Product p=products.get(item.getType());double line=round(sellPrice(p)*item.getAmount());
            if(line<=0)continue;
            lines.add(new SaleLine(i,item.getType(),item.getAmount(),line));count+=item.getAmount();total+=line;
        }
        return new Sale(lines,count,round(total));
    }
    private void confirm(Player player) {
        Sale sale=scan(player);
        if(sale.lines.isEmpty()){message(player,"nothing-to-sell");return;}
        Inventory inv=inventory(View.CONFIRM,null,null,0,27,text(getConfig().getString("shop.confirmation-title","Confirmar venta")));
        inv.setItem(11,nav("accept-material","<green><bold>ACEPTAR VENTA"));
        inv.setItem(13,nav("info-material","<yellow>Vender "+sale.quantity+" objetos","<green>Recibir: "+money(sale.total),"<gray>No incluye armadura ni segunda mano"));
        inv.setItem(15,nav("cancel-material","<red><bold>CANCELAR"));player.openInventory(inv);
    }
    private void sellAll(Player player) {
        Sale sale=scan(player);
        if(sale.lines.isEmpty()){message(player,"nothing-to-sell");return;}
        EconomyResponse r=economy.depositPlayer(player,sale.total);
        if(!r.transactionSuccess()){message(player,"sale-failed");return;}
        for(SaleLine line:sale.lines) {
            ItemStack current=player.getInventory().getItem(line.slot);
            if(current==null || current.getType()!=line.material || current.getAmount()!=line.quantity || !eligible(current)) {
                // Synchronous inventory events prevent changes between scan and removal.
                getLogger().severe("Inventory changed during sellall for " + player.getName());continue;
            }
            player.getInventory().setItem(line.slot,null);
        }
        message(player,"sellall-done","quantity",""+sale.quantity,"amount",money(sale.total));
    }
    @Override public boolean onCommand(@NotNull CommandSender sender,@NotNull Command command,@NotNull String label,@NotNull String[] args) {
        if(command.getName().equalsIgnoreCase("bastionshop")) {
            if(!sender.hasPermission("bastionmcshop.admin")){sender.sendMessage(text("<red>Sin permiso"));return true;}
            if(args.length==1&&args[0].equalsIgnoreCase("reload")) {
                reloadConfig();loadCatalog();sender.sendMessage(text(getConfig().getString("messages.reloaded","Recargado")));return true;
            }
            sender.sendMessage(text(getConfig().getString("messages.invalid-command","/bastionshop reload")));return true;
        }
        if(!(sender instanceof Player player)){sender.sendMessage("Solo jugadores.");return true;}
        if(command.getName().equalsIgnoreCase("shop")) {
            if(!player.hasPermission("bastionmcshop.use")){message(player,"no-permission");return true;}
            openRoot(player,0);return true;
        }
        if(command.getName().equalsIgnoreCase("sellall")) {
            if(!getConfig().getBoolean("sellall.enabled",true)||!player.hasPermission("bastionmcshop.sellall")){message(player,"no-permission");return true;}
            if(args.length==0){confirm(player);return true;}
            if(args.length==1&&args[0].equalsIgnoreCase("fast")) {
                if(!getConfig().getBoolean("sellall.fast-enabled",true)||!player.hasPermission("bastionmcshop.sellall.fast")){message(player,"no-permission");return true;}
                sellAll(player);return true;
            }
            player.sendMessage(text("<yellow>Uso: /sellall [fast]"));return true;
        }
        return false;
    }
    @EventHandler public void onClick(InventoryClickEvent event) {
        if(!(event.getView().getTopInventory().getHolder() instanceof ShopHolder h))return;
        event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player player))return;
        int slot=event.getRawSlot();if(slot<0||slot>=event.getView().getTopInventory().getSize())return;
        switch(h.view) {
            case ROOT -> {
                if(slot==45&&h.page>0)openRoot(player,h.page-1);
                else if(slot==53)openRoot(player,h.page+1);
                else if(slot<45){List<Category> cs=new ArrayList<>(categories.values());int index=h.page*45+slot;if(index<cs.size())openCategory(player,cs.get(index).key,0);}
            }
            case CATEGORY -> {
                if(slot==48)openRoot(player,0);
                else if(slot==45&&h.page>0)openCategory(player,h.category,h.page-1);
                else if(slot==53)openCategory(player,h.category,h.page+1);
                else if(slot<45){Category c=categories.get(h.category);int index=h.page*45+slot;if(c!=null&&index<c.materials.size())openProduct(player,c.materials.get(index),h.page);}
            }
            case PRODUCT -> {
                if(slot==49){openCategory(player,h.category,h.page);return;}
                Product p=products.get(h.product);if(p==null)return;
                int[] buySlots={20,21,23,24},sellSlots={29,30,32,33},amounts={1,16,32,h.product.getMaxStackSize()};
                for(int i=0;i<4;i++){
                    int q=Math.min(amounts[i],h.product.getMaxStackSize());
                    if(slot==buySlots[i]){safeBuy(player,p,q);return;}
                    if(slot==sellSlots[i]){sell(player,p,q);return;}
                }
            }
            case CONFIRM -> {
                if(slot==11){player.closeInventory();sellAll(player);}
                else if(slot==15)player.closeInventory();
            }
        }
    }
}
