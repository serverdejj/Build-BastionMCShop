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
import org.bukkit.inventory.meta.SkullMeta;
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
    private int[] slots(String path, int[] fallback) {
        List<Integer> values=getConfig().getIntegerList(path);
        if(values.isEmpty()) return fallback;
        Set<Integer> used=new HashSet<>();
        for(Integer slot:values) if(slot==null||slot<0||slot>=54||!used.add(slot)) {
            getLogger().warning("Slots invalidos en "+path+"; usando valores predeterminados.");return fallback;
        }
        return values.stream().mapToInt(Integer::intValue).toArray();
    }
    private int[] displaySlots(View view) {
        return view==View.ROOT ? slots("shop.layout.root.slots",new int[]{20,22,24,29,31,33,38,40})
            : slots("shop.layout.category.slots",new int[]{10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43});
    }
    private int pageCount(int count,int capacity) { return Math.max(1,(count+capacity-1)/capacity); }
    private int slot(String key,int fallback){int v=getConfig().getInt("shop.layout."+key,fallback);return v>=0&&v<54?v:fallback;}
    private String ui(String key,String fallback,String... replacements) {
        String value=getConfig().getString("shop.texts."+key,fallback);
        for(int i=0;i+1<replacements.length;i+=2)value=value.replace("{"+replacements[i]+"}",replacements[i+1]);
        return value;
    }
    private List<String> uiLore(String key,List<String> fallback,String... replacements){
        List<String> values=getConfig().getStringList("shop.texts."+key);
        if(values.isEmpty())values=fallback;
        List<String> result=new ArrayList<>();
        for(String value:values){for(int i=0;i+1<replacements.length;i+=2)value=value.replace("{"+replacements[i]+"}",replacements[i+1]);result.add(value);}
        return result;
    }
    private ItemStack named(Material material,String key,String fallback,String... replacements){
        List<String> lore=uiLore(key+"-lore",List.of(),replacements);
        return icon(material,ui(key,fallback,replacements),lore.toArray(String[]::new));
    }
    private Material configuredMaterial(String path,Material fallback) {
        Material m=Material.matchMaterial(getConfig().getString(path,fallback.name()));
        return m!=null&&m.isItem()?m:fallback;
    }
    private void decorate(Inventory inv,Player player,String layout) {
        String base="shop.layout."+layout;
        Material border=configuredMaterial(base+".border-material",Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        ItemStack panel=icon(border,ui("panel-name"," "),uiLore("panel-lore",List.of()).toArray(String[]::new));
        for(int i=0;i<inv.getSize();i++)inv.setItem(i,null);
        boolean enabled=getConfig().getBoolean(base+".border-enabled",true);
        if(enabled)for(int i=0;i<inv.getSize();i++){
            int row=i/9,col=i%9;
            if(row==0||row==inv.getSize()/9-1||col==0||col==8)inv.setItem(i,panel.clone());
        }
        for(int i:getConfig().getIntegerList(base+".extra-panels"))if(i>=0&&i<inv.getSize())inv.setItem(i,panel.clone());
        int head=inv.getSize()==27?getConfig().getInt(base+".head-slot",22):slot(layout+".head-slot",49);
        if(head>=0&&head<inv.getSize())inv.setItem(head,playerInfo(player));
    }
    private ItemStack playerInfo(Player player) {
        ItemStack head=new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta=(SkullMeta)head.getItemMeta();meta.setOwningPlayer(player);
        String name=ui("head-name","<aqua><bold>MI SALDO","player",player.getName(),"balance",money(economy.getBalance(player)));
        meta.displayName(text(name));
        meta.lore(uiLore("head-lore",List.of("<gray>Jugador: <white>{player}","<green>Dinero: <white>{balance}"),"player",player.getName(),"balance",money(economy.getBalance(player))).stream().map(this::text).toList());
        head.setItemMeta(meta);return head;
    }
    private void refreshBalance(Inventory inv,Player player,String layout) {
        int head=inv.getSize()==27?getConfig().getInt("shop.layout."+layout+".head-slot",22):slot(layout+".head-slot",49);
        if(head>=0&&head<inv.getSize())inv.setItem(head,playerInfo(player));
    }
    private ItemStack control(String key,String materialKey,String defaultName,String... replacements) {
        Material m=configuredMaterial("shop."+materialKey,Material.ARROW);
        return named(m,key,defaultName,replacements);
    }
    private void openRoot(Player player,int page) {
        List<Category> list=new ArrayList<>(categories.values());int[] positions=displaySlots(View.ROOT);
        int pages=pageCount(list.size(),positions.length);page=Math.max(0,Math.min(page,pages-1));
        Inventory inv=inventory(View.ROOT,null,null,page,54,text(ui("root-title",getConfig().getString("shop.title","BASTION SHOP"),"page",""+(page+1),"pages",""+pages)));
        decorate(inv,player,"root");
        for(int i=0;i<positions.length&&page*positions.length+i<list.size();i++){
            Category c=list.get(page*positions.length+i);
            inv.setItem(positions[i],named(c.icon,"category-name","{category}","category",c.name,"key",c.key));
            ItemMeta meta=inv.getItem(positions[i]).getItemMeta();
            meta.lore(uiLore("category-lore",List.of("<yellow>Click para ver productos"),"category",c.name,"key",c.key).stream().map(this::text).toList());inv.getItem(positions[i]).setItemMeta(meta);
        }
        if(page>0)inv.setItem(slot("root.previous-slot",45),control("previous-name","previous-material","<yellow>Anterior"));
        if(page+1<pages)inv.setItem(slot("root.next-slot",53),control("next-name","next-material","<green>Siguiente"));
        inv.setItem(slot("root.page-slot",47),named(Material.PAPER,"page-name","<yellow>Página {page}/{pages}","page",""+(page+1),"pages",""+pages));
        player.openInventory(inv);
    }
    private void openCategory(Player player,String key,int page) {
        Category c=categories.get(key);if(c==null){openRoot(player,0);return;}
        int[] positions=displaySlots(View.CATEGORY);int pages=pageCount(c.materials.size(),positions.length);page=Math.max(0,Math.min(page,pages-1));
        String title=ui("category-title",getConfig().getString("shop.category-title","{category} ({page}/{pages})"),"category",c.name,"page",""+(page+1),"pages",""+pages);
        Inventory inv=inventory(View.CATEGORY,key,null,page,54,text(title));decorate(inv,player,"category");
        for(int i=0;i<positions.length&&page*positions.length+i<c.materials.size();i++){
            Material m=c.materials.get(page*positions.length+i);Product p=products.get(m);
            String[] replacements={"item",pretty(m),"buy",money(p.buy),"sell",money(sellPrice(p))};
            ItemStack item=named(m,"product-name","<white>{item}",replacements);
            ItemMeta meta=item.getItemMeta();meta.lore(uiLore("product-lore",List.of("<green>Comprar: {buy}","<red>Vender: {sell}","<yellow>Click para ver opciones"),replacements).stream().map(this::text).toList());item.setItemMeta(meta);
            inv.setItem(positions[i],item);
        }
        inv.setItem(slot("category.back-slot",48),control("back-name","back-material","<gold>Volver"));
        if(page>0)inv.setItem(slot("category.previous-slot",45),control("previous-name","previous-material","<yellow>Anterior"));
        if(page+1<pages)inv.setItem(slot("category.next-slot",53),control("next-name","next-material","<green>Siguiente"));
        inv.setItem(slot("category.page-slot",47),named(Material.PAPER,"page-name","<yellow>Página {page}/{pages}","page",""+(page+1),"pages",""+pages));
        player.openInventory(inv);
    }
    private void openProduct(Player player,Material m,int categoryPage) {
        Product p=products.get(m);if(p==null)return;
        Inventory inv=inventory(View.PRODUCT,p.category,m,categoryPage,54,text(ui("item-title",getConfig().getString("shop.product-title","{item}"),"item",pretty(m))));
        decorate(inv,player,"item");int max=m.getMaxStackSize();
        inv.setItem(slot("item.preview-slot",13),named(m,"item-preview-name","<white>{item}","item",pretty(m)));
        int[] amounts={1,16,32,max},buys=slots("shop.layout.item.buy-slots",new int[]{20,22,24,26}),sells=slots("shop.layout.item.sell-slots",new int[]{29,31,33,35});
        for(int i=0;i<4;i++){
            int q=Math.min(amounts[i],max);
            String[] buyArgs={"quantity",""+q,"price",money(round(p.buy*q)),"item",pretty(m)};
            String[] sellArgs={"quantity",""+q,"price",money(round(sellPrice(p)*q)),"item",pretty(m)};
            if(i<buys.length)inv.setItem(buys[i],control("buy-name","buy-material","<green>COMPRAR x{quantity}",buyArgs));
            if(i<sells.length)inv.setItem(sells[i],control("sell-name","sell-material","<red>VENDER x{quantity}",sellArgs));
            if(i<buys.length){ItemStack it=inv.getItem(buys[i]);ItemMeta meta=it.getItemMeta();meta.lore(uiLore("buy-lore",List.of("<gray>Precio: {price}"),buyArgs).stream().map(this::text).toList());it.setItemMeta(meta);}
            if(i<sells.length){ItemStack it=inv.getItem(sells[i]);ItemMeta meta=it.getItemMeta();meta.lore(uiLore("sell-lore",List.of("<gray>Recibes: {price}"),sellArgs).stream().map(this::text).toList());it.setItemMeta(meta);}
        }
        inv.setItem(slot("item.back-slot",48),control("back-name","back-material","<gold>Volver"));player.openInventory(inv);
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
        decorate(inv,player,"confirm");
        inv.setItem(getConfig().getInt("shop.layout.confirm.accept-slot",11),control("accept-name","accept-material","<green><bold>ACEPTAR VENTA"));
        ItemStack info=control("confirm-info-name","info-material","<yellow>Vender {quantity} objetos","quantity",""+sale.quantity,"total",money(sale.total));
        ItemMeta im=info.getItemMeta();im.lore(uiLore("confirm-info-lore",List.of("<green>Recibir: {total}","<gray>No incluye armadura ni segunda mano"),"quantity",""+sale.quantity,"total",money(sale.total)).stream().map(this::text).toList());info.setItemMeta(im);
        inv.setItem(getConfig().getInt("shop.layout.confirm.info-slot",13),info);
        inv.setItem(getConfig().getInt("shop.layout.confirm.cancel-slot",15),control("cancel-name","cancel-material","<red><bold>CANCELAR"));player.openInventory(inv);
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
    private int displayIndex(int slot,View view) {int[] slots=displaySlots(view);for(int i=0;i<slots.length;i++)if(slots[i]==slot)return i;return -1;}
    @EventHandler public void onClick(InventoryClickEvent event) {
        if(!(event.getView().getTopInventory().getHolder() instanceof ShopHolder h))return;
        event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player player))return;
        int slot=event.getRawSlot();if(slot<0||slot>=event.getView().getTopInventory().getSize())return;
        switch(h.view) {
            case ROOT -> {
                if(slot==slot("root.previous-slot",45)&&h.page>0)openRoot(player,h.page-1);
                else if(slot==slot("root.next-slot",53))openRoot(player,h.page+1);
                else if(slot<54){List<Category> cs=new ArrayList<>(categories.values());int pos=displayIndex(slot,View.ROOT);int index=h.page*displaySlots(View.ROOT).length+pos;if(pos>=0&&index<cs.size())openCategory(player,cs.get(index).key,0);}
            }
            case CATEGORY -> {
                if(slot==slot("category.back-slot",48))openRoot(player,0);
                else if(slot==slot("category.previous-slot",45)&&h.page>0)openCategory(player,h.category,h.page-1);
                else if(slot==slot("category.next-slot",53))openCategory(player,h.category,h.page+1);
                else if(slot<54){Category c=categories.get(h.category);int pos=displayIndex(slot,View.CATEGORY);int index=h.page*displaySlots(View.CATEGORY).length+pos;if(pos>=0&&c!=null&&index<c.materials.size())openProduct(player,c.materials.get(index),h.page);}
            }
            case PRODUCT -> {
                if(slot==slot("item.back-slot",48)){openCategory(player,h.category,h.page);return;}
                Product p=products.get(h.product);if(p==null)return;
                int[] buySlots=slots("shop.layout.item.buy-slots",new int[]{20,22,24,26}),sellSlots=slots("shop.layout.item.sell-slots",new int[]{29,31,33,35}),amounts={1,16,32,h.product.getMaxStackSize()};
                for(int i=0;i<4;i++){
                    int q=Math.min(amounts[i],h.product.getMaxStackSize());
                    if(i<buySlots.length&&slot==buySlots[i]){safeBuy(player,p,q);refreshBalance(event.getView().getTopInventory(),player,"item");return;}
                    if(i<sellSlots.length&&slot==sellSlots[i]){sell(player,p,q);refreshBalance(event.getView().getTopInventory(),player,"item");return;}
                }
            }
            case CONFIRM -> {
                if(slot==getConfig().getInt("shop.layout.confirm.accept-slot",11)){player.closeInventory();sellAll(player);}
                else if(slot==getConfig().getInt("shop.layout.confirm.cancel-slot",15))player.closeInventory();
            }
        }
    }
}
